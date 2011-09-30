package scalanlp.parser
package discrim

import ParseChart._
import projections._
import scalanlp.trees.BinarizedTree


/**
 * 
 * @author dlwh
 */
@SerialVersionUID(1)
trait EPApproximator[C,F,W] extends Serializable {
  def project(inside: ParseChart[F], outside: ParseChart[F], partition: Double, spanScorer: SpanScorer[F], goldTree: BinarizedTree[C]):SpanScorer[C]
  def divide(num: SpanScorer[C], denom: SpanScorer[C], words: Seq[W]):SpanScorer[C]
}


@SerialVersionUID(1)
class AnchoredRuleApproximator[C,F,W](fineParser: ChartBuilder[LogProbabilityParseChart,F,W],
                                      coarseParser: ChartBuilder[LogProbabilityParseChart,C,W],
                                      projections: GrammarProjections[C,F], pruningThreshold: Double = Double.NegativeInfinity) extends EPApproximator[C,F,W] with Serializable {
  val factory = new AnchoredRuleScorerFactory[C,F,W](coarseParser.grammar, fineParser,projections,pruningThreshold);

  val zeroFactory = new CachingSpanScorerFactory[C,W](coarseParser);

  def project(words: Seq[W], spanScorer: SpanScorer[F], tree: BinarizedTree[C] = null):SpanScorer[C] = {
    val inside = fineParser.buildInsideChart(words,spanScorer)
    val outside = fineParser.buildOutsideChart(inside,spanScorer)
    val partition = inside.top.labelScore(0,words.length,fineParser.root)
    project(inside,outside,partition,spanScorer,tree)
  }

  def project(inside: ParseChart[F], outside: ParseChart[F], partition: Double, spanScorer: SpanScorer[F], tree: BinarizedTree[C]):SpanScorer[C] = {
    factory.buildSpanScorer(inside, outside,  partition, spanScorer, tree);
  }

  def divide(num: SpanScorer[C], denom: SpanScorer[C], words: Seq[W]):SpanScorer[C] ={
    val div = ScalingSpanScorer(num,denom,0.0,-1);
    zeroFactory.mkSpanScorer(words,div);
  }
}
