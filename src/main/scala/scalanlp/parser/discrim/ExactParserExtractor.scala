package scalanlp.parser
package discrim

import scalanlp.parser.ParseChart._
import scalanlp.parser.projections.GrammarProjections
import collection.mutable.ArrayBuffer
import scalanlp.parser._
import projections.GrammarProjections._
import scalala.tensor.{Counter, Counter2}
import scalala.tensor.::
import java.io.File
import scalanlp.trees.UnaryChainRemover.ChainReplacer
import scalanlp.util._
import logging.ConfiguredLogging
import scalala.library.Library
import Library.{sum,norm}
import scalanlp.optimize.CachedBatchDiffFunction
import scalala.tensor.dense.DenseVector

/**
 * 
 * @author dlwh
 */

object ExactParserExtractor {

  type Label[L,L2] = (L,Seq[L2])

  def extractParser[L,L2,W](parsers: Seq[ChartBuilder[LogProbabilityParseChart,L2,W]],
                            coarseParser: ChartBuilder[LogProbabilityParseChart,L,W],
                            projections: Seq[GrammarProjections[L,L2]]):ChartParser[L,(L,Seq[L2]),W] = {

    type MyLabel = Label[L,L2]

    def project(l: MyLabel) = l._1
    def refine(l: L):Seq[MyLabel] = {
      val options = IndexedSeq[IndexedSeq[L2]](Vector.empty)
      val r = projections.foldLeft(options){ (options,p) =>
        for(l2 <- p.labels.refinementsOf(l); o <- options) yield {
          val r: IndexedSeq[L2] = o :+ l2
          r
        }
      }
      r.map(l ->  _)
    }

    val myProjections = GrammarProjections(coarseParser.grammar, refine _,  project _)
    val grammars = parsers.map(_.grammar)

    val brules = Counter2[MyLabel,BinaryRule[(L,Seq[L2])], Double]()
    val urules = Counter2[MyLabel,UnaryRule[(L,Seq[L2])], Double]()
    for(r <- myProjections.rules.fineIndex) r match {
      case br@BinaryRule(a,b,c) =>
        val scores = for {
          i <- 0 until grammars.length;
          aa = a._2 apply i
          bb = b._2 apply i
          cc = c._2 apply i
          g = grammars(i)
          score = g.ruleScore(BinaryRule(aa,bb,cc))
        } yield score

        brules(a,br) = scores.sum
      case ur@UnaryRule(a,b) =>
        val scores = for {
          i <- 0 until grammars.length;
          aa = a._2 apply i
          bb = b._2 apply i
          g = grammars(i)
        } yield g.ruleScore(UnaryRule(aa,bb))
        urules(a,ur) = scores.sum

    }

    val grammar = Grammar(myProjections.labels.fineIndex, myProjections.rules.fineIndex, brules, urules)

    val lexicons = parsers.map(_.lexicon)

    val _knownTagWords = collection.mutable.Set[(MyLabel,W)]()
    val knownTags = coarseParser.lexicon.knownTagWords.map(_._1).flatMap(myProjections.labels.refinementsOf _).toSet
    val knownWords = coarseParser.lexicon.knownTagWords.map(_._2).toSet
    for( w <- knownWords; ll1 <- lexicons(0).tagScores(w).keysIterator; ref <- myProjections.labels.refinementsOf(projections(0).labels.project(ll1))) {
      _knownTagWords += (ref->w)
    }

    def scoreWord(label: (L,Seq[L2]), w: W):Double = {
      var score = 0.0
      for( (lex,l) <- lexicons zip label._2) {
        val s = lex.wordScore(l,w)
        if(s == Double.NegativeInfinity)
          return s
        score += s
      }
      score
    }

    val lexicon = new Lexicon[MyLabel,W] {

      def wordScore(label: MyLabel, w: W):Double = {
        scoreWord(label,w)
      }


      override def tagScores(w: W) = {
        val scores = lexicons.map(_.tagScores(w));
        val res = Counter[MyLabel,Double]()
        for( ll1 <- scores(0).keysIterator; ref <- myProjections.labels.refinementsOf(projections(0).labels.project(ll1))) {
          val allScores = for (
            (sc,label) <- scores zip ref._2
          ) yield sc(label)
          res(ref) = allScores.sum
        }
        res
      }

      def tags = knownTags.iterator

      def knownTagWords = _knownTagWords.iterator
    }

    val root = myProjections.labels.refinementsOf(coarseParser.root)(0)
    val builder = new CKYChartBuilder[ParseChart.LogProbabilityParseChart,MyLabel,W](root, lexicon, grammar, ParseChart.logProb)
    new ChartParser(builder, new MaxConstituentDecoder[L,MyLabel,W](myProjections), myProjections)

  }


}

object ExactTrainer extends ParserTrainer {
  type Params = LatentParams[SpecificParams]
  case class SpecificParams(numParsers: Int = 2, numSatelliteStates: Int=2)

  protected lazy val paramManifest = implicitly[Manifest[Params]]
  type MyLabel = ((String,Int),Seq[L2])
  type L2 = (String,Int)

  def split(l: String, numStates: Int, numSatellites: IndexedSeq[Int]): IndexedSeq[MyLabel] = {
    if(l == "") {
      IndexedSeq((l -> 0,IndexedSeq.fill(numSatellites.length)(l -> 0)))
    } else {
      val options = IndexedSeq[IndexedSeq[L2]](Vector.empty)
      val r = numSatellites.foldLeft(options){ (options,myStates) =>
        for(l2 <- 0 until myStates; o <- options) yield {
          val r: IndexedSeq[L2] = o :+ (l->l2)
          r
        }
      }
      for(i <- 0 until numStates; o <- options) yield (l -> i) -> o
    }
  }

  def unsplit(l: MyLabel) = l._1._1

  def getFeaturizer(params: Params,
                    initLexicon: Counter2[String, String, Double],
                    initBinaries: Counter2[String, BinaryRule[String], Double],
                    initUnaries: Counter2[String, UnaryRule[String], Double],
                    numCoreStates: Int,
                    satellites: IndexedSeq[Int]) = {
    val factory = params.featurizerFactory;
    val featurizer = factory.getFeaturizer(initLexicon, initBinaries, initUnaries);
    val latentFactory = params.latentFactory;
    val latentFeaturizer = latentFactory.getFeaturizer(featurizer, numCoreStates);
    val satelliteFeats = satellites.map(latentFactory.getFeaturizer(featurizer,_))
    new SatelliteFeaturizer[(String,Int),L2,String](latentFeaturizer,satelliteFeats)
  }

  def trainParser(trainTrees: IndexedSeq[TreeInstance[String, String]],
                  devTrees: IndexedSeq[TreeInstance[String, String]],
                  unaryReplacer: ChainReplacer[String], params: Params) = {

    val (initLexicon,initBinaries,initUnaries) = GenerativeParser.extractCounts(trainTrees);
    import params._;
    val numParsers = params.specific.numParsers;
    val numSatellites = params.specific.numSatelliteStates;
    println("NumStates: " + params.numStates);
    println("NumParsers: " + params.specific.numParsers);
    println("NumSatellites: " + params.specific.numSatelliteStates);

    val xbarParser = parser.optParser.getOrElse {
      val grammar = Grammar(Library.logAndNormalizeRows(initBinaries),Library.logAndNormalizeRows(initUnaries));
      val lexicon = new SimpleLexicon(initLexicon);
      new CKYChartBuilder[LogProbabilityParseChart,String,String]("",lexicon,grammar,ParseChart.logProb);
    }


    val satellites = Array.fill(numParsers)(numSatellites)
    val indexedProjections = GrammarProjections(xbarParser.grammar, split(_:String,numStates, satellites), unsplit);

    val latentFeaturizer = getFeaturizer(params, initLexicon, initBinaries, initUnaries, numStates, satellites)

    val openTags = Set.empty ++ {
      for(t <- initLexicon.nonzero.keys.map(_._1) if initLexicon(t,::).size > 50; t2 <- split(t, numStates, satellites).iterator ) yield t2;
    }

    val closedWords = Set.empty ++ {
      val wordCounts = sum(initLexicon)
      wordCounts.nonzero.pairs.iterator.filter(_._2 > 5).map(_._1);
    }

    val obj = new LatentDiscrimObjective[String,MyLabel,String](latentFeaturizer,
      trainTrees,
      indexedProjections,
      xbarParser,
      openTags,
      closedWords) with ConfiguredLogging;

    val optimizer = opt.minimizer(obj);

    val init = obj.initialWeightVector + 0.0;

    import scalanlp.optimize.RandomizedGradientCheckingFunction;
    val rand = new RandomizedGradientCheckingFunction(obj,1E-4);
    def evalAndCache(pair: (optimizer.State,Int) ) {
      val (state,iter) = pair;
      val weights = state.x;
      if(iter % iterPerValidate == 0) {
        cacheWeights(params, obj,weights, iter);
        quickEval(obj, unaryReplacer, devTrees, weights);
      }
    }


    val cachedObj = new CachedBatchDiffFunction[DenseVector[Double]](obj);

    for( (state,iter) <- optimizer.iterations(cachedObj,init).take(maxIterations).zipWithIndex.tee(evalAndCache _);
         if iter != 0 && iter % iterationsPerEval == 0) yield try {
      val parser = obj.extractParser(state.x)
      ("LatentDiscrim-" + iter.toString,parser)
    } catch {
      case e => println(e);e.printStackTrace(); throw e;
    }
  }

  def cacheWeights(params: Params, obj: LatentDiscrimObjective[String,MyLabel,String], weights: DenseVector[Double], iter: Int) = {
    writeObject( new File("weights-"+iter +".ser"), weights -> obj.indexedFeatures.decode(weights));
  }

    def quickEval(obj: AbstractDiscriminativeObjective[String,MyLabel,String],
                unaryReplacer : ChainReplacer[String],
                devTrees: Seq[TreeInstance[String,String]], weights: DenseVector[Double]) {
    println("Validating...");
    val parser = obj.extractParser(weights);
    val fixedTrees = devTrees.take(400).toIndexedSeq;
    val results = ParseEval.evaluate(fixedTrees, parser, unaryReplacer);
    println("Validation : " + results)
  }
}

object ExactRunner extends ParserTrainer {

  case class Params(parser: ParserParams.BaseParser,
                    model0: File = null,
                    model1: File = null,
                    model2: File = null,
                    model3: File = null)
  protected val paramManifest = manifest[Params]

  def trainParser(trainTrees: IndexedSeq[TreeInstance[String,String]],
                  devTrees: IndexedSeq[TreeInstance[String,String]],
                  unaryReplacer : ChainReplacer[String],
                  params: Params) = {
    val parsers = new ArrayBuffer[ChartParser[String,(String,Int),String]]
    var found = true
    var i = 0
    val paths = params.productIterator.buffered
    while(found && paths.hasNext) {
      found = false
      while(paths.hasNext && !paths.head.isInstanceOf[File]) paths.next
      if(paths.hasNext) {
        val path = paths.next.asInstanceOf[File]
        println(path)
        if(path ne null) {
          parsers += readObject(path)
          found = true
        }
        i += 1
      }
    }
    val coarseParser = params.parser.optParser

    val productParser = ExactParserExtractor.extractParser(parsers.map(_.builder.withCharts(ParseChart.logProb)), coarseParser.get, parsers.map(_.projections))
    Iterator.single( "Exact" -> productParser)
  }


}

object SplitExact extends ParserTrainer {

  case class Params(parser: ParserParams.BaseParser,
                    featurizerFactory: FeaturizerFactory[String,String] = new PlainFeaturizerFactory[String],
                    weightsPath: File, numStates: Int)

  protected val paramManifest = implicitly[Manifest[Params]]


  def split(x: String, numStates: Int) = {
    if(x.isEmpty) Seq((x,0))
    else for(i <- 0 until numStates) yield (x,i)
  }

  def unsplit(x: (String,Int)) = x._1


  def trainParser(trainTrees: IndexedSeq[TreeInstance[String, String]],
                  devTrees: IndexedSeq[TreeInstance[String, String]],
                  unaryReplacer: ChainReplacer[String], params: Params) = {

    import params._

    type MyLabel = ((String,Int),Seq[(String,Int)])
    val features = scalanlp.util.readObject[(Any,Counter[Feature[MyLabel,String],Double])](weightsPath)._2
    val featuresByIndex = Counter2[Int,Feature[(String,Int),String],Double]()
    for( (SatelliteFeature(SubstateFeature(baseV,states2),SubstateFeature(f,states),i),v) <- features.pairsIterator) {
      featuresByIndex(i,SubstateFeature(f,ArrayBuffer(states:_*))) += v
    }

    val (initLexicon,initBinaries,initUnaries) = GenerativeParser.extractCounts(trainTrees)

    val xbarParser: ChartBuilder[ParseChart.LogProbabilityParseChart, String, String] = params.parser.optParser.getOrElse {
      val grammar = Grammar(Library.logAndNormalizeRows(initBinaries),Library.logAndNormalizeRows(initUnaries))
      val lexicon = new SimpleLexicon(initLexicon)
      new CKYChartBuilder[LogProbabilityParseChart,String,String]("",lexicon,grammar,ParseChart.logProb)
    }


    val indexedProjections = GrammarProjections(xbarParser.grammar, split(_:String,numStates), unsplit)

    val featurizer = featurizerFactory.getFeaturizer(initLexicon, initBinaries, initUnaries)
    val latentFactory = new SlavLatentFeaturizerFactory
    val baseFeaturizer = latentFactory.getFeaturizer(featurizer, numStates)

    val openTags = Set.empty ++ {
      for(t <- initLexicon.nonzero.keys.map(_._1) if initLexicon(t,::).size > 50; t2 <- split(t, numStates).iterator ) yield t2
    }

    val closedWords = Set.empty ++ {
      val wordCounts = sum(initLexicon)
      wordCounts.nonzero.pairs.iterator.filter(_._2 > 5).map(_._1)
    }


    val obj = new LatentDiscrimObjective(baseFeaturizer, trainTrees, indexedProjections, xbarParser, openTags, closedWords)
    println("Current:")
    println(obj.indexedFeatures.index)
    val parsers = {for( i <- featuresByIndex.domain._1.iterator) yield {
      println("Model " + i)
      println(featuresByIndex(i,::))
      val init = obj.indexedFeatures.encodeDense(featuresByIndex(i,::))
      println(i)
      println("Norm: " + norm(init,2))
      println("Norm counter: " + norm(featuresByIndex(i,::),2))
      println("Number zeros: " + init.toArray.count(_ == 0.0))
      println("Number zeros counter: " + featuresByIndex(i,::).valuesIterator.count(_ == 0.0))
      val parser = obj.extractParser(init)
      ("Split-" + i) -> parser

    }}.toIndexedSeq

    val coarseParser = ChartParser(xbarParser)
    val projections = IndexedSeq.fill(parsers.length)(indexedProjections)
    val ep = new EPParser(parsers.map(_._2.builder.withCharts(ParseChart.logProb)), coarseParser, projections, 4)
    val adf = new EPParser(parsers.map(_._2.builder.withCharts(ParseChart.logProb)), coarseParser, projections, 1)
    val product = new ProductParser(parsers.map(_._2.builder.withCharts(ParseChart.logProb)), xbarParser, projections)
    val ep8 = new EPParser(parsers.map(_._2.builder.withCharts(ParseChart.logProb)), coarseParser, projections, 8)
    val exact = ExactParserExtractor.extractParser(parsers.map(_._2.builder.withCharts(ParseChart.logProb)), xbarParser, projections)

    parsers.iterator ++ Iterator("EP"-> ep, "ADF" -> adf, "Product" -> product, "EP-8" -> ep8, "Exact" -> exact)


  }
}