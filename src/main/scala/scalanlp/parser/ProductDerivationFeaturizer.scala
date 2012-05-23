package scalanlp.parser

import scalanlp.util.EitherIndex

/**
 *
 * @author dlwh
 */

class ProductDerivationFeaturizer[L, W, Feat1, Feat2](sf1: DerivationScorer.Factory[L, W],
                                        sf2: DerivationScorer.Factory[L, W],
                                        feat1: DerivationFeaturizer[L, W, Feat1],
                                        feat2: DerivationFeaturizer[L, W, Feat2]) extends DerivationFeaturizer[L, W, Either[Feat1, Feat2]] {
  def index: EitherIndex[Feat1, Feat2] = feat1.index | feat2.index

  def specialize(w: Seq[W]):Specialization = {
    val s1 = sf1.specialize(w)
    val s2 = sf2.specialize(w)
    val f1 = feat1.specialize(w)
    val f2 = feat1.specialize(w)
    new ProductRefinementsHandler[L, W](s1, s2) with Specialization {
      def words = w

      def featuresForBinaryRule(begin: Int, split: Int, end: Int, rule: Int, ref: Int) = {
        val arr1 = f1.featuresForBinaryRule(begin, split, end, rule, rule1Ref(rule, ref))
        val arr2 = f2.featuresForBinaryRule(begin, split, end, rule, rule2Ref(rule, ref))
        arr1 ++ arr2.map(_ + index.rightOffset)
      }

      def featuresForUnaryRule(begin: Int, end: Int, rule: Int, ref: Int) = {
        val arr1 = f1.featuresForUnaryRule(begin, end, rule, rule1Ref(rule, ref))
        val arr2 = f2.featuresForUnaryRule(begin, end, rule, rule2Ref(rule, ref))
        arr1 ++ arr2.map(_ + index.rightOffset)
      }

      def featuresForSpan(begin: Int, end: Int, label: Int, ref: Int) = {
        val arr1 = f1.featuresForSpan(begin, end, label, label1Ref(label, ref))
        val arr2 = f2.featuresForSpan(begin, end, label, label2Ref(label, ref))
        arr1 ++ arr2.map(_ + index.rightOffset)
      }
    }
  }
}