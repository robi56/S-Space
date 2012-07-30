package edu.ucla.sspace

import edu.ucla.sspace.matrix.Matrices
import edu.ucla.sspace.matrix.PointWiseMutualInformationTransform
import edu.ucla.sspace.similarity.CosineSimilarity
import edu.ucla.sspace.vector.CompactSparseVector
import edu.ucla.sspace.vector.VectorMath

import scala.collection.JavaConversions.seqAsJavaList
import scala.io.Source
import scala.util.Random

import java.io.PrintWriter


object BatchTweets {
    val lambda = 0.5
    val beta = 100
    val w = (0.45, 0.45, 0.10)
    val simFunc = new CosineSimilarity()
    var useMedian = false 

    def main(args: Array[String]) {
        val config = Config(args(0))

        def sim(t1: Tweet, t2: Tweet) = Tweet.sim(t1, t2, lambda, beta, w, simFunc)

        useMedian = args(1) match {
            case "median" => true
            case "mean" => false
            case _ => throw new IllegalArgumentException("Not a valid argument for the median")
        }
        val converter = config.featureModel.get match {
            case "split" => TweetModeler.split(config.tokenBasis.get, config.neBasis.get)
            case "joint" => TweetModeler.joint(config.tokenBasis.get, config.neBasis.get, config.ngramSize.get)
            case _ => throw new IllegalArgumentException("Not a valid argument for the Tweet Modeler")
        }

        val tweetArray = converter.tweetIterator(config.taggedFile.get).toArray
        val tweets = tweetArray.toList

        val k = config.numGroups.get
        val assignments = Array.fill(tweets.size)(-1)
        // Extract a random set of tweets to act as medians.
        var medianList = Random.shuffle(tweets).take(k).sortWith(_.timestamp <= _.timestamp)
        var medianUpdated = true

        while (medianUpdated) {
            println("Starting full iteration")
            // Assign the first set of tweets, i.e. those before the first median, to the first median.
            val (firstGroup, remainingTweets) = tweets.span(_.timestamp < medianList.head.timestamp)
            var offset = assignTweets(firstGroup, assignments, 0, 0)

            // Iterate through each pairing of medians to compute the best cut point between each pair.  After computing the best cut point,
            // assign the tweets to their nearest medians.
            val lastItems = medianList.zipWithIndex
                                      .sliding(2)
                                      .foldLeft(remainingTweets)( (tweetList, medianPair) => {
                // Get the medians and their group identifiers.
                val ((m1, i1), (m2, i2)) = (medianPair.head, medianPair.last)
                // Get the sequence of tweets between the two medians.  Span will do this since the list starts just after the first median.
                val (window, rest) = tweetList.span(_.timestamp <= m2.timestamp)
                // Compute the object for the first possible cut location, i.e. everything is assigned to m2.
                var objectiveValue = sim(m1, m1) + window.map(t=>sim(t, m2)).sum
                // For each possible cut position, update the objective function and emit the value and timestamp.  After considering every
                // possible cut location, find the one with the highest objective score.
                val bestTime = window.map( t => {
                    // Updated objective value.
                    objectiveValue = objectiveValue - sim(t, m2) + sim(t, m1)
                    // Emiting value and timestamp of the cut.
                    (objectiveValue, t.timestamp)
                }).max._2
                // With the cut point, get the group of points assigned to each median.
                val (g1, g2) = window.span(_.timestamp <= bestTime)
                // Make the assignment of points to medians.
                offset = assignTweets(g1, assignments, i1, offset)
                offset = assignTweets(g2, assignments, i2, offset)

                // The rest list contains all points after the second median.  Use this for future processing.
                rest
            })
            // Assign the last set of tweets, i.e. those after the last median, to the last median.
            assignTweets(lastItems, assignments, k-1, offset)

            // For each group, compute the best median within the group.  This will simply be the tweet that maximizes the internal similarity
            // within the group.
            val newMedianList = assignments.zip(tweets)
                       .groupBy(_._1)
                       .map{ case (medianIndex, group) => {
                val points = group.map(_._2)
                if (useMedian) {
                      points(points.map(pmedian => points.map(point => sim(pmedian, point)).sum)
                                   .zipWithIndex.max._2)
                } else {
                    val median = points(points.map(pmedian => points.map(point => sim(pmedian, point)).sum)
                                                .zipWithIndex.max._2)
                    val mean = points.foldLeft( converter.emptyTweet )( _+_ )
                    new Tweet(median.timestamp, mean.tokenVector, mean.neVector, median.text)
                }
            }}.toList.sortWith(_.timestamp < _.timestamp)

            medianUpdated = newMedianList.map(_.timestamp) != medianList.map(_.timestamp)
            medianList = newMedianList
        }

        val p = new PrintWriter(config.groupOutput.get)
        p.println("Time Group")
        assignments.zip(tweets).foreach(x => p.println("%d %d".format(x._2.timestamp, x._1)))
        p.close

        val t = new PrintWriter(config.splitOutput.get)
        t.println("Time")
        medianList.map(_.timestamp).foreach(t.println)
        t.close

        val s = new PrintWriter(config.summaryOutput.get)
        s.println("Summary")
        medianList.map(_.text).foreach(s.println)
        s.close

        val transform = new PointWiseMutualInformationTransform()
        val clusterMatrix = Matrices.asSparseMatrix(medianList.map(_.tokenVector))
        val weightedMatrix = transform.transform(clusterMatrix)

        val m = new PrintWriter(config.featureOutput.get)
        m.println("Top Words")
        medianList.map(_.tokenVector).map(v => v.getNonZeroIndices
                                                .map(i => (v.get(i), i))
                                                .sorted
                                                .reverse
                                                .take(10)
                                                .map(_._2)
                                                .map(converter.token)
                                                .mkString(" "))
                                     .foreach(m.println)
        m.close
    }

    def assignTweets(items: List[Tweet], assignments: Array[Int], groupId: Int, offset: Int) = {
        for (i <- 0 until items.size)
            assignments(offset+i) = groupId
        offset + items.size
    }
}
