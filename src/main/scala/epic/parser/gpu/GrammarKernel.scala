package epic.parser
package gpu

import epic.parser.SimpleRefinedGrammar
import epic.trees._
import annotations.FilterAnnotations
import annotations.{TreeAnnotator, FilterAnnotations}
import com.nativelibs4java.opencl._
import java.{util, lang}
import scala.Array
import breeze.config.{Configuration, CommandLineParser}
import java.io.{FileWriter, File}
import collection.mutable.ArrayBuffer
import breeze.collection.mutable.TriangularArray
import com.nativelibs4java.opencl.CLMem.Usage
import breeze.linalg.{Counter, DenseVector}
import org.bridj.Pointer
import breeze.util.{Index, Encoder}
import collection.{immutable, mutable}
import java.nio.{FloatBuffer, ByteBuffer}
import projections.{GrammarRefinements, ProjectionIndexer}

class GrammarKernel[C, L, W](
//                          projections: GrammarRefinements[C, L],
                          grammar: BaseGrammar[L],
                          lexicon: Lexicon[L, W],
                          private var _ruleScores: Array[RuleScores],
                          tagScores: Array[(IndexedSeq[W], Int, Int)=>Double],
                          maxSentences: Int = 1000)(implicit val context: CLContext) {
  def ruleScores = _ruleScores

  val numGrammars = _ruleScores.length
  val structure = RuleStructure[L](grammar)

  val (inside, outside, ecounts, copyPosTags) = {
    import GrammarKernel._
    import structure.{grammar=>_, _ }
    val insideKernel = new InsideKernel(structure, numGrammars)
    val outsideKernel = new OutsideKernel(structure, numGrammars)
    val ecountsKernel = new ExpectedCountsKernel(structure, numGrammars)

    val headerText = GrammarHeader.header(structure, numGrammars)
    if(true) {val o = new FileWriter("inside.cl");  o.write(insideKernel.text); o.close()}
    if(true) {val o = new FileWriter("outside.cl"); o.write(outsideKernel.text); o.close()}
    if(true) {val o = new FileWriter("ecounts.cl"); o.write(ecountsKernel.text); o.close()}

    val inside = insideKernel
    val outside = outsideKernel
    val copyPos = context.createProgram(headerText, copyPosToCharts)
    copyPos.setUnsafeMathOptimizations()
    copyPos.setFastRelaxedMath()

    (inside, outside, ecountsKernel, copyPos)
  }

  val nsyms = grammar.labelIndex.size
  val nrules = grammar.index.size
  val nbinaries = ruleScores.head.binaries.length
  val nunaries = ruleScores.head.unaries.length
  val root = grammar.labelIndex(grammar.root)
  val totalRules: Int = nbinaries * numGrammars + nunaries * numGrammars
  val cellSize = nsyms * numGrammars
  val (maxCells, maxTotalLength) = {
    val totalBytes = context.getDevices.map(_.getGlobalMemSize).min
    val maxSize = ((totalBytes / cellSize).toInt / 4 / 12).toInt
    val maxTotalLength = (context.getMaxMemAllocSize / totalRules / 4).toInt min (maxSize * cellSize / totalRules)

    maxSize ->  maxTotalLength
  }

  private implicit val queue = context.createDefaultProfilingQueue()
  private val copyTags = copyPosTags.createKernel("copy_pos_to_charts")
  private val sumVector = context.createProgram(GrammarKernel.sumECountVectors).createKernel("sum_vectors")
  private val memZero = new ZeroMemoryKernel

  private val insideTopDev, insideBotDev = context.createFloatBuffer(Usage.InputOutput, maxCells * cellSize)
  private val outsideTopDev, outsideBotDev = context.createFloatBuffer(Usage.InputOutput, maxCells * cellSize)
  private val ecountsDev = context.createFloatBuffer(Usage.InputOutput, maxTotalLength * totalRules)
  private val termECountsDev = context.createFloatBuffer(Usage.InputOutput, maxTotalLength * cellSize)
  private val offDev = context.createIntBuffer(Usage.Input, maxSentences)
  private val offPtr = offDev.allocateCompatibleMemory(context.getDevices()(0))
  private val offLengthsDev = context.createIntBuffer(Usage.Input, maxSentences)
  private val offLengthsPtr = offLengthsDev.allocateCompatibleMemory(context.getDevices()(0))
  private val lenDev = context.createIntBuffer(Usage.Input, maxSentences)
  private val lenPtr = lenDev.allocateCompatibleMemory(context.getDevices()(0))
  private val ruleVector = Pointer.allocateFloats(totalRules)

  private val posTagsDev = context.createFloatBuffer(Usage.InputOutput, maxTotalLength * cellSize)
  private val posTagsPtr = posTagsDev.allocateCompatibleMemory(context.getDevices()(0))

  private val rulesDev = context.createFloatBuffer(Usage.Input, totalRules)
  ruleScores = _ruleScores

  def ruleScores_=(newRules: Array[RuleScores]) {
    _ruleScores = newRules
    val arr = new Array[Float](rulesDev.getElementCount.toInt)
    for(g <- 0 until numGrammars) {
      for(b <- 0 until ruleScores(g).binaries.length) {
        arr(b * numGrammars + g) = math.exp(ruleScores(g).binaries(b)).toFloat
      }
      for(u <- 0 until ruleScores(g).unaries.length) {
        arr(nbinaries * numGrammars + u * numGrammars + g) = math.exp(ruleScores(g).unaries(u)).toFloat
      }
    }
    val pointer = Pointer.pointerToFloats(arr:_*)
    rulesDev.write(queue, pointer, true)
    pointer.release()
  }


  def parse(sentences: IndexedSeq[IndexedSeq[W]]):IndexedSeq[BinarizedTree[L]] = synchronized {
    {for {
      partition <- getPartitions(sentences).iterator
      batch = layoutIntoMemory(partition)
      t <- doParse(batch)
    } yield {
      t
    }}.toIndexedSeq
  }

  def expectedRuleCounts(sentences: IndexedSeq[IndexedSeq[W]]): (DenseVector[Double], Array[Array[Counter[W, Double]]]) = synchronized {
    val allCounts = for {
      partition <- getPartitions(sentences).iterator
    } yield {
      val batch = layoutIntoMemory(partition)
      val (counts: Array[Float], wordCounts: Array[Array[Counter[W, Double]]]) = doExpectedCounts(batch)
      val r = new DenseVector[Double](counts.map(_.toDouble))
      r -> wordCounts
    }

    def sumCounts(words: Array[Array[Counter[W, Double]]], words2: Array[Array[Counter[W, Double]]]) {
      for( (a,b) <- words zip words2; (a2,b2) <- a zip b) a2 += b2
    }

    allCounts.reduceOption{ (c1, c2) => c1._1 += c2._1; sumCounts(c1._2, c2._2); c1}.getOrElse(DenseVector.zeros[Double](totalRules) -> Array.fill(numGrammars, nsyms)(Counter[W, Double]()))
  }

  case class Batch(lengths: Array[Int],
                   offsets: Array[Int],
                   lengthTotals: Array[Int],
                   totalLength: Int,
                   sentences: IndexedSeq[IndexedSeq[W]],
                   posTags: Array[Float]) {

  }


  private def layoutIntoMemory(sentences: IndexedSeq[IndexedSeq[W]]): Batch = {
    val lengths = sentences.map(_.length)
    val offsets = new ArrayBuffer[Int]()

    var offset = 0

    val partialLengths = new Array[Int](lengths.size)
    var totalLength = 0
    var i = 0
    while(i < partialLengths.length) {
      partialLengths(i) = totalLength
      totalLength += lengths(i)
      i += 1
    }
    assert(maxTotalLength >= totalLength, maxTotalLength -> totalLength)

    val posTags = new Array[Float](totalLength * cellSize)

    for( (s, i) <- sentences.zipWithIndex) {
      offsets += offset
      for(pos <- (0 until s.length);
          aa <- lexicon.tagsForWord(s(pos));
          a = grammar.labelIndex(aa)) {
        for(g <- 0 until numGrammars)
          posTags(((partialLengths(i) + pos) * nsyms +  a)*numGrammars + g) = math.exp(tagScores(g)(s, pos, a)).toFloat
      }


      offset += TriangularArray.arraySize(s.length+1)
    }
    offsets += offset

    Batch(lengths.toArray, offsets.toArray, partialLengths, totalLength, sentences, posTags)
  }

  private def getPartitions(sentences: IndexedSeq[IndexedSeq[W]]): IndexedSeq[IndexedSeq[IndexedSeq[W]]] = {
    val result = ArrayBuffer[IndexedSeq[IndexedSeq[W]]]()
    var current = ArrayBuffer[IndexedSeq[W]]()
    var currentCellTotal = 0
    var currentLengthTotal = 0
    for(s <- sentences) {
      currentCellTotal += TriangularArray.arraySize(s.length+1)
      currentLengthTotal += s.length
      if(currentCellTotal > maxCells || current.size >= maxSentences || currentLengthTotal > maxTotalLength) {
        assert(current.nonEmpty)
        result += current
        currentCellTotal = TriangularArray.arraySize(s.length+1)
        currentLengthTotal = s.length
        current = ArrayBuffer[IndexedSeq[W]]()
      }
      current += s
    }

    if(current.nonEmpty) result += current
    result
  }

  private def doParse(batch: Batch):IndexedSeq[BinarizedTree[L]] = synchronized {
    var lastEvent = insideOutside(batch)
//    val marginals = getMarginals(batch)

//    println(marginals.mkString("\n...\n"))
//    println(marginals.map(_.rootScore(0)).mkString("\n"))
//    println(marginals.map(m => breeze.numerics.logSum((0 until grammar.labelIndex.size).map(i => m.topOutsideScore(0,1,0, i) + m.topInsideScore(0, 1, 0, i)))))


    IndexedSeq.empty
  }

  private def doExpectedCounts(batch: Batch) = synchronized {
    import batch._
    val wOB = memZero.zeroMemory(ecountsDev)
    val wterm = memZero.zeroMemory(termECountsDev)
    var lastEvent = insideOutside(batch)
//    var lastEvent = wOB


    offLengthsPtr.setInts(lengthTotals)
    val copyOffLengths = offLengthsDev.write(queue, offLengthsPtr, false)

    lastEvent = ecounts.expectedCounts(ecountsDev, termECountsDev,
      insideBotDev, insideTopDev,
      outsideBotDev,
      outsideTopDev,
      offDev, lenDev, offLengthsDev, lengths.max, rulesDev, lastEvent, wOB, copyOffLengths, wterm)


    val termVector = Pointer.allocateFloats(totalLength * cellSize)
    val termOut = termECountsDev.read(queue, termVector, true, lastEvent)
    val floats = termVector.getFloats
    termVector.release()
    val wordEcounts = tallyTermExpectedCounts(floats, sentences, lengthTotals)

    lastEvent = collapseArray(ecountsDev, totalLength, totalRules, lastEvent)

    queue.finish()
    val wobCount = (wOB.getProfilingCommandEnd - wOB.getProfilingCommandStart) / 1E9
//    val termCount = (termOut.getProfilingCommandEnd - termOut.getProfilingCommandStart) / 1E9

    println("write: " + wobCount)

    ecountsDev.read(queue, 0, totalRules, ruleVector, true,  lastEvent)
    val arr = ruleVector.getFloats
    arr -> wordEcounts
  }

  private def tallyTermExpectedCounts(counts: Array[Float], words: IndexedSeq[IndexedSeq[W]], offsets: Array[Int]) = {
    val r = Array.fill(numGrammars, nsyms)(Counter[W, Double]())
    for(s <- 0 until words.length; g <- 0 until numGrammars; i <- 0 until words(s).length; sym <- 0 until nsyms) {
      val count = counts( ((offsets(s) + i) * nsyms + sym)*numGrammars + g)
      if(count != 0) {
        r(g)(sym)(words(s)(i)) += count.toDouble
      }
    }

    r
  }

  private def collapseArray(v: CLBuffer[lang.Float], len: Int, width: Int, toAwait: CLEvent) = {
    var lastEvent = toAwait
    var numCellsLeft = len
    while(numCellsLeft > 1) {
      val half = numCellsLeft / 2
      numCellsLeft -= half
      sumVector.setArg(0, v)
      sumVector.setArg(1, half * width) // don't go past the first half, rounded down.
      sumVector.setArg(2, numCellsLeft * width) // pull from the corresponding second half.
      // the reason these are different are for odd splits.
      // if there are 5 cells remaining, we want to sum the last two into the first two, and then
      // the third into the first, and then the second into the first.
      lastEvent = sumVector.enqueueNDRange(queue, Array(half * width), lastEvent)
    }
    lastEvent
  }


  private def getMarginals(batch: Batch) =  {
    val lastEvent = insideOutside(batch)
    import batch._

    val inM = insideTopDev.read(queue, lastEvent)
    val outM = outsideTopDev.read(queue, lastEvent)
    val inBM = insideBotDev.read(queue, lastEvent)
    val outBM = outsideBotDev.read(queue, lastEvent)
    val inTop = inM.getFloats
    val outTop = outM.getFloats
    val inBot = inBM.getFloats
    val outBot = outBM.getFloats
    inM.release()
    outM.release()
    inBM.release()
    outBM.release()

    for (i <- 0 until lengths.length) yield {
      val off = offsets(i)
      val len = lengths(i)
      Marginal(inTop, inBot, outTop, inBot, off, len)
    }
  }


  private def insideOutside(batch: Batch) = synchronized {
    import batch._
    val maxLength = lengths.max
    posTagsPtr.setFloats(batch.posTags)
    offPtr.setInts(offsets)
    lenPtr.setInts(lengths)

//    println(posTags.slice(batch.lengthTotals(1) * nsyms, batch.totalLength * nsyms).mkString(", "))

    val wPos = posTagsDev.write(queue, 0, batch.posTags.length, posTagsPtr, false)
    val wIB = memZero.zeroMemory(insideBotDev)
    val wIT = memZero.zeroMemory(insideTopDev)
    val wOT = memZero.zeroMemory(outsideTopDev)
    val wOB = memZero.zeroMemory(outsideBotDev)
    val wO = offDev.write(queue, 0, lengths.length, offPtr, false)
    val wL = lenDev.write(queue, 0, lengths.length, lenPtr, false)
    offLengthsPtr.setInts(lengthTotals)
    val copyOffLengths = offLengthsDev.write(queue, offLengthsPtr, false)

    copyTags.setArgs(posTagsDev, insideBotDev, offDev, lenDev, offLengthsDev, Integer.valueOf(1))
    val maxDim1Size = queue.getDevice.getMaxWorkItemSizes()(0)
    if(maxDim1Size < nsyms * numGrammars) {
      copyTags.setArg(5, numGrammars / 8 + 1)
    }
    val gramMultiplier = if(maxDim1Size < nsyms * numGrammars) {
      8
    } else {
      numGrammars
    }
    val initCharts = copyTags.enqueueNDRange(queue,  Array(nsyms * gramMultiplier, lengths.length, maxLength), Array(nsyms * gramMultiplier, 1, 1), wPos, wIB, wL, copyOffLengths)

    var lastU = inside.insidePass(insideBotDev, insideTopDev, offDev, lenDev, maxLength, rulesDev, initCharts, wIT)
    lastU = outside.outsidePass(outsideTopDev, outsideBotDev, insideTopDev, offDev, lenDev, maxLength, rulesDev, lastU, wOB, wOT)


    queue.finish()


    val writeCounts = IndexedSeq(wIT, wOT, wIB, wOB, wO, wL, initCharts).map(e => e.getProfilingCommandEnd - e.getProfilingCommandStart).sum / 1E9

    println(writeCounts)
    lastU
  }

  override protected def finalize() {
    offPtr.release()
    lenPtr.release()
    offDev.release()
    lenDev.release()
    outsideTopDev.release()
    insideTopDev.release()
    outsideBotDev.release()
    insideBotDev.release()
  }

  case class Marginal(inTop: Array[Float], inBot: Array[Float], outTop: Array[Float], outBot: Array[Float], offset: Int, length: Int) {
    def topInsideScore(begin: Int, end: Int, grammar: Int, label: Int) =  {
      val score = inTop(index(begin, end, grammar, label))
//      java.lang.Math.scalb(score, -10 * ((end-begin)-1))
      math.log(score) - 10 * ((end-begin)-1) * math.log(2)
    }


    def botInsideScore(begin: Int, end: Int, grammar: Int, label: Int) =  {
      val score = inBot(index(begin, end, grammar, label))
//      java.lang.Math.scalb(score, -10 * ((end-begin)-1))
//      math.log(score)
      math.log(score) - 10 * ((end-begin)-1) * math.log(2)
    }

    def topOutsideScore(begin: Int, end: Int, grammar: Int, label: Int) =  {
      val score = outTop(index(begin, end, grammar, label))
//      java.lang.Math.scalb(score, -10 * ((end-begin)-1))
      math.log(score) - 10 * (length-(end-begin)) * math.log(2)
    }


    def botOutsideScore(begin: Int, end: Int, grammar: Int, label: Int) =  {
      val score = outBot(index(begin, end, grammar, label))
//      java.lang.Math.scalb(score, -10 * ((end-begin)-1))
//      math.log(score)
      math.log(score) - 10 * (length-(end-begin)) * math.log(2)
    }

    @inline
    private def index(begin: Int, end: Int, grammar: Int, label: Int): Int = {
      ((offset + TriangularArray.index(begin, end)) * nsyms + label) * numGrammars + grammar
    }

    override def toString() = {
      val pieces = for {
        i <- 0 until length
        end <- (i+1) to length
        l <- 0 until nsyms
        ts = topOutsideScore(i, end, 0, l)
        bs = botOutsideScore(i, end, 0, l)
        if !ts.isInfinite || !bs.isInfinite
      } yield {
        (i,end,l) + " " + ts + " " + bs
      }

      pieces.mkString("\n")
    }

    def rootScore(grammar: Int) = topInsideScore(0, length, grammar, root)
  }
}

object GrammarKernel {
  case class Params(annotator: TreeAnnotator[AnnotatedLabel, String, AnnotatedLabel] = FilterAnnotations(),
                    useGPU: Boolean = true, numToParse: Int = 1000, numGrammars: Int = 1)

  def main(args: Array[String]) {
    import ParserParams.JointParams

    val (baseConfig, files) = CommandLineParser.parseArguments(args)
    val config = baseConfig backoff Configuration.fromPropertiesFiles(files.map(new File(_)))
    val params = try {
      config.readIn[JointParams[Params]]("test")
    } catch {
      case e =>
        e.printStackTrace()
        println(breeze.config.GenerateHelp[JointParams[Params]](config))
        sys.exit(1)
    }

    if(params.help) {
      println(breeze.config.GenerateHelp[JointParams[Params]](config))
      System.exit(1)
    }
    import params._
    import params.trainer._
    println("Training Parser...")
    println(params)
    val annotator = FilterAnnotations[String]()
    val transformed = params.treebank.trainTrees.par.map { ti => annotator(ti) }.seq.toIndexedSeq
    val grammar = GenerativeParser.extractGrammar(AnnotatedLabel.TOP, transformed)

    val kern = fromSimpleGrammar(grammar, params.trainer.useGPU, numGrammars)
    println("Parsing...")
    val train = transformed.slice(0,numToParse)
    val timeIn = System.currentTimeMillis()
    kern.parse(train.map(_.words.toIndexedSeq))
    println("Done: " + (System.currentTimeMillis() - timeIn))
    println("ecounts...")
    val time2 = System.currentTimeMillis()
    val counts = kern.expectedRuleCounts(train.map(_.words.toIndexedSeq))
    val time3 = System.currentTimeMillis()
    println(counts._1.sum)
    println(counts._2.map(_.map(_.sum).sum).sum)
//    println(Encoder.fromIndex(grammar.refinedGrammar.index).decode(counts))
    println("Done ecounts: " + (time3 - time2))
    val timeX = System.currentTimeMillis()
    val feat = new ProductionFeaturizer(grammar.grammar, grammar.lexicon.knownLexicalProductions)
    val marg = train.map(_.words).foldLeft(DenseVector.zeros[Double](feat.index.size)){ (acc, s) =>
      val m = ChartMarginal(AugmentedGrammar.fromRefined(grammar), s, ParseChart.logProb)
      val counts = m.expectedCounts(feat).counts
//      println(m.partition)
      acc += counts
      acc
    }
    println("Done: " + (System.currentTimeMillis() - timeX))
    println(marg.slice(0, grammar.grammar.index.size).sum)
    println(marg.slice(grammar.grammar.index.size, marg.length).sum)

//    println(Encoder.fromIndex(grammar.grammar.index).decode(marg.slice(0, grammar.grammar.index.size)))
//    def unroll(m: ChartMarginal[ParseChart.LogProbabilityParseChart, AnnotatedLabel, String]) = {
//      for(l <- 0 until grammar.labelIndex.size; ref <- grammar.refinements.labels.localRefinements(l)) yield{
//        m.outside.top(0,1,l, ref)
//      }
//      m.partition
//    }
//    println(marg)
  }

  def fromSimpleGrammar[L, L2, W](grammar: SimpleRefinedGrammar[L, L2, W], useGPU: Boolean = true, numGrammars: Int = 1) = {
    import grammar.refinedGrammar._
    val (binaryRules, unaryRules) = (0 until index.size).partition(isBinary(_))
    val sortedBinary: IndexedSeq[Int] = binaryRules.sortBy{r1 => (leftChild(r1), parent(r1), rightChild(r1))}(Ordering.Tuple3)
    val sortedUnary = unaryRules.sortBy(r => parent(r) -> child(r))(Ordering.Tuple2)

    val unaryRuleScores = sortedUnary.map { r => indexedRule(r).asInstanceOf[UnaryRule[Int]] -> (r-binaryRules.length)}
    val binaryRuleScores = sortedBinary.map { r => indexedRule(r).asInstanceOf[BinaryRule[Int]] -> r }


    implicit val context = if(useGPU) {
      JavaCL.createBestContext()
    } else {
      val cpuPlatform = JavaCL.listPlatforms().filter(_.listCPUDevices(true).nonEmpty).head
      cpuPlatform.createContext(new java.util.HashMap(), cpuPlatform.listCPUDevices(true):_*)
    }
    println(context)

    println(grammar.refinedGrammar.labelIndex)

    val rscores = RuleScores.fromRefinedGrammar(grammar)
    val grammars = new Array[RuleScores](numGrammars)
    util.Arrays.fill(grammars.asInstanceOf[Array[AnyRef]], rscores)
    // segfaults java. your guess is as good as mine.
//    val grammars2 = Array.fill(numGrammars)(RuleScores.fromRefinedGrammar(grammar, numBits))
    val scorers = Array.fill(numGrammars){ (w: IndexedSeq[W], pos: Int, label: Int) =>
      grammar.anchor(w).scoreSpan(pos, pos+1, label, 0)
    }

    val kern = new GrammarKernel(grammar.grammar, grammar.lexicon, grammars, scorers)

    kern
  }










  private def projectMarginalsKernel[L, L2](projections: ProjectionIndexer[L, L2], odds_ratio: Boolean) = {
    """
#define NUM_PROJECTED_SYMS  %d
typedef {
  float top[NUM_PROJECTED_SYMS][NUM_GRAMMARS], bot[NUM_PROJECTED_SYMS][NUM_GRAMMARS];
} projected_parse_cell;


typedef {
  float syms[NUM_PROJECTED_SYMS][NUM_GRAMMARS];
} projected_symbols;

__kernel void project_nterms(__global projected_parse_cell* projected,
                             __global const parse_cell* insides_top,
                             __global const parse_cell* insides,
                             __global const parse_cell* outsides,
                             __global const int* offsets,
                             __global const int* lengths) {
  const int sentence = get_global_id(0);
  const int begin = get_global_id(1);
  const int gram = get_global_id(2);
  const int end = begin + spanLength;
  const int length = lengths[sentence];

  if(end <= length) {
    __global const parse_cell* out = outsides + offsets[sentence];
    __global const parse_cell* in = insides + offsets[sentence];
    __global const parse_cell* itop = insides_top + offsets[sentence];
    const float root_score = CELL(itop, 0, length)[ROOT][gram]; // scale is 2^(SCALE_FACTOR)^(length-1)
    __global const parse_cell* in = CELL(inside, begin, end);
    __global const parse_cell* out = CELL(outside, begin, end);
    __global projected_symbols* target = CELL(projected + offsets[sentence], begin, end)

    %s
  }

}

    """.format(projections.coarseIndex.size, projectNonterminalsInner(projections, odds_ratio))
  }


  private def projectNonterminalsInner[L, L2](projections: ProjectionIndexer[L, L2], odds_ratio: Boolean) = {
    val buf = new ArrayBuffer[String]()
    if(odds_ratio)
      buf += "float sum = 0.0;"
    buf += "float cur;"
    for(coarse <- 0 until projections.coarseIndex.size) {
      buf += "cur = 0.0;"
      for(ref <- projections.refinementsOf(coarse)) {
        buf += "cur = mad(in->syms[%d][gram], out->syms[%d][gram], cur);".format(ref, ref)
      }
      if(odds_ratio) {
        buf += "sum += cur;"
        buf += "target->syms[%d][gram] = cur;".format(coarse)
      } else {
        buf += "target->syms[%d][gram] = cur/root_score;".format(coarse)
      }
    }

    if(odds_ratio) {
      buf += "const float norm = root_score - sum;"
      buf += "for (int i = 0; i < NUM_PROJECTED_SYMS; ++i) {"
      buf += "  target->syms[%d][gram] /= norm;"
      buf += "}"
    }
    buf.mkString("\n      ")
  }


  private val copyPosToCharts =
  """
__kernel void copy_pos_to_charts(
  __global const parse_cell* pos_tags,
  __global parse_cell * insides_bot,
  __global const int* offsets,
  __global const int* lengths,
  __global const int* lengthOffsets,
  const int numGrammarsToDo) {
  const int sym = get_global_id(0)/ NUM_GRAMMARS;
  int grammar = get_global_id(0) % NUM_GRAMMARS;
  const int sentence = get_global_id(1);
  const int begin = get_global_id(2);
  const int end = begin  + 1;
  const int length = lengths[sentence];
  if (begin < length) {
    __global parse_cell* inside = insides_bot + offsets[sentence];
    __global parse_cell* in = CELL(inside, begin, end);
    __global const parse_cell* mybuf = pos_tags + (lengthOffsets[sentence] + begin);
    // ibot has scale 0, obot has scale length - 1, root_score has scale length - 1. Woot.
    for(int i = 0; i < numGrammarsToDo && grammar < NUM_GRAMMARS; ++i) {
      in->syms[sym][grammar] = mybuf->syms[sym][grammar];
      grammar += (NUM_GRAMMARS / numGrammarsToDo);
    }
  }
}"""




  private val sumECountVectors =
    """
__kernel void sum_vectors(__global float* vec, const int maxLen, int pivot) {
  int trg = get_global_id(0);
  if(trg < maxLen)
    vec[trg] += vec[trg + pivot];
}
    """

}