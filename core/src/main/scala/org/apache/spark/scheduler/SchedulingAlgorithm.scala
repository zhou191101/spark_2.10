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

package org.apache.spark.scheduler

/**
  * An interface for sort algorithm
  * FIFO: FIFO algorithm between TaskSetManagers
  * FS: FS algorithm between Pools, and FIFO or FS within Pools
  */
private[spark] trait SchedulingAlgorithm {
  def comparator(s1: Schedulable, s2: Schedulable): Boolean
}

private[spark] class FIFOSchedulingAlgorithm extends SchedulingAlgorithm {
  override def comparator(s1: Schedulable, s2: Schedulable): Boolean = {
    val priority1 = s1.priority
    val priority2 = s2.priority
    // 比较两个的优先级，值越小，优先级越高
    var res = math.signum(priority1 - priority2)
    // 如果优先级相同，则对s1和s2所属对Stage对身份标识进行比较
    if (res == 0) {
      val stageId1 = s1.stageId
      val stageId2 = s2.stageId
      res = math.signum(stageId1 - stageId2)
    }
    // 结果小于0，返回true，说明s1优先级高于s2
    res < 0
  }
}

/*
*  1、如果s1中处于运行状态的Task数量小于s1的minShare，并且s2的Task数量大于s2的minShare，那么优先调度s1
*  2、如果s1中处于运行状态的Task数量大于等于s1的minShare，并且s2中处于运行状态的Task数量小于s2的minShare，那么优先调度s2
*  3、如果s1中处于运行状态的Task数量小于s1的minShare，并且s2中处于运行状态的Task数量小于s2的minShare，那么再对minShareRatio1
*  和民ShareRatio2进行比较。如果minShareRatio1小于minShareRatio2，则优先调度s1；如果minShareRatio2小于minShareRatio1
*  则优先调度s2。如果minShareRatio1和minShareRatio2相等，还需要对s1和s2的名字进行比较。如果s1的名字小于s2的名字，则优先
*  调度s1，否则优先调度s2。minShareRatio是正在运行的任务数量与minShare之间的比值
*  4、如果s1中处于运行状态的Task数量大于等于s1的minShare，并且s2中处于运行状态的Task数量大于等于s2的minShare，那么再对
*  taskToWeightRatio1和taskToWeightRatio2进行比较。如果taskToWeightRatio1小于taskToWeightRatio2，则优先调度s1；
*  如果taskToWeightRatio2小于taskToWeightRatio1，则优先调度s2。如果taskToWeightRatio1和taskToWeightRatio2相等，
*  还需要对s1和s2的名字进行比较。如果s1的名字小于s2的名字，则优先调度s1，否则优先调度s2。taskToWeightRatio是正在运行的
*  任务数量与权重之间的比值
* */
private[spark] class FairSchedulingAlgorithm extends SchedulingAlgorithm {
  override def comparator(s1: Schedulable, s2: Schedulable): Boolean = {
    val minShare1 = s1.minShare
    val minShare2 = s2.minShare
    val runningTasks1 = s1.runningTasks
    val runningTasks2 = s2.runningTasks

    val s1Needy = runningTasks1 < minShare1
    val s2Needy = runningTasks2 < minShare2
    val minShareRatio1 = runningTasks1.toDouble / math.max(minShare1, 1.0)
    val minShareRatio2 = runningTasks2.toDouble / math.max(minShare2, 1.0)
    val taskToWeightRatio1 = runningTasks1.toDouble / s1.weight.toDouble
    val taskToWeightRatio2 = runningTasks2.toDouble / s2.weight.toDouble

    var compare = 0
    if (s1Needy && !s2Needy) {
      return true
    } else if (!s1Needy && s2Needy) {
      return false
    } else if (s1Needy && s2Needy) {
      compare = minShareRatio1.compareTo(minShareRatio2)
    } else {
      compare = taskToWeightRatio1.compareTo(taskToWeightRatio2)
    }
    if (compare < 0) {
      true
    } else if (compare > 0) {
      false
    } else {
      s1.name < s2.name
    }
  }
}

