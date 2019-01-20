/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.util.collection

import scala.collection.mutable

import org.apache.spark.util.SizeEstimator

/**
  * A general interface for collections to keep track of their estimated sizes in bytes.
  * We sample with a slow exponential back-off using the SizeEstimator to amortize the time,
  * as each call to SizeEstimator is somewhat expensive (order of a few milliseconds).
  */
private[spark] trait SizeTracker {

  import SizeTracker._

  /**
    * Controls the base of the exponential which governs the rate of sampling.
    * E.g., a value of 2 would mean we sample at 1, 2, 4, 8, ... elements.
    * 采样增长的速率。例如，速率为2时，分别对1，2，4，8，……位置上的元素进行采样。
    */
  private val SAMPLE_GROWTH_RATE = 1.1

  /**
    * Samples taken since last resetSamples(). Only the last two are kept for extrapolation.
    * 样本队列，最后两个样本将用于估算
    *
    * */
  private val samples = new mutable.Queue[Sample]

  /** The average number of bytes per update between our last two samples. */
  // 平均每次更新的字节数
  private var bytesPerUpdate: Double = _

  /** Total number of insertions and updates into the map since the last resetSamples(). */
  // 更新操作的总次数
  private var numUpdates: Long = _

  /** The value of 'numUpdates' at which we will take our next sample. */
  private var nextSampleNum: Long = _

  resetSamples()

  /**
    * Reset samples collected so far.
    * This should be called after the collection undergoes a dramatic change in size.
    */
  protected def resetSamples(): Unit = {
    numUpdates = 1
    nextSampleNum = 1
    samples.clear()
    takeSample()
  }

  /**
    * Callback to be invoked after every update.
    */
  protected def afterUpdate(): Unit = {
    numUpdates += 1
    if (nextSampleNum == numUpdates) {
      takeSample()
    }
  }

  /**
    * Take a new sample of the current collection's size.
    */
  private def takeSample(): Unit = {
    // 估算集合的大小并作为样本
    samples.enqueue(Sample(SizeEstimator.estimate(this), numUpdates))
    // Only use the last two samples to extrapolate
    // 保留样本队列的最后两个样本
    if (samples.size > 2) {
      samples.dequeue()
    }
    val bytesDelta = samples.toList.reverse match {
      case latest :: previous :: tail =>
        (latest.size - previous.size).toDouble / (latest.numUpdates - previous.numUpdates)
      // If fewer than 2 samples, assume no change
      case _ => 0
    }
    // 计算每次更新的字节数
    bytesPerUpdate = math.max(0, bytesDelta)
    // 根据采样增长速率计算nextSampleNum的值
    nextSampleNum = math.ceil(numUpdates * SAMPLE_GROWTH_RATE).toLong
  }

  /**
    * Estimate the current size of the collection in bytes. O(1) time.
    */
  def estimateSize(): Long = {
    assert(samples.nonEmpty)
    val extrapolatedDelta = bytesPerUpdate * (numUpdates - samples.last.numUpdates)
    (samples.last.size + extrapolatedDelta).toLong
  }
}

private object SizeTracker {

  case class Sample(size: Long, numUpdates: Long)

}
