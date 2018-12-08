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

package org.apache.spark.util

import java.util.concurrent.CopyOnWriteArrayList

import scala.collection.JavaConverters._
import scala.reflect.ClassTag
import scala.util.control.NonFatal

import org.apache.spark.internal.Logging

/**
 * An event bus which posts events to its listeners.
 */
// L是代表监听器的范型参数，E代表事件的范型参数
private[spark] trait ListenerBus[L <: AnyRef, E] extends Logging {

  // Marked `private[spark]` for access in tests.
  // 用于维护所有注册的监听器，器数据结构为CopyOnWriteArrayList
  private[spark] val listeners = new CopyOnWriteArrayList[L]

  /**
   * Add a listener to listen events. This method is thread-safe and can be called in any thread.
   */
  final def addListener(listener: L): Unit = {
    listeners.add(listener)
  }

  /**
   * Remove a listener and it won't receive any events. This method is thread-safe and can be called
   * in any thread.
   */
  final def removeListener(listener: L): Unit = {
    listeners.remove(listener)
  }

  /**
   * Post the event to all registered listeners. The `postToAll` caller should guarantee calling
   * `postToAll` in the same thread for all events.
   */
  final def postToAll(event: E): Unit = {
    // JavaConverters can create a JIterableWrapper if we use asScala.
    // However, this method will be called frequently. To avoid the wrapper cost, here we use
    // Java Iterator directly.
    val iter = listeners.iterator
    while (iter.hasNext) {
      // 先检查后执行，因此是非线程安全的，因此要保证执行这个方法的线程只有一个
      val listener = iter.next()
      try {
        doPostEvent(listener, event)
      } catch {
        case NonFatal(e) =>
          logError(s"Listener ${Utils.getFormattedClassName(listener)} threw an exception", e)
      }
    }
  }

  /**
   * Post an event to the specified listener. `onPostEvent` is guaranteed to be called in the same
   * thread for all listeners.
   */
  protected def doPostEvent(listener: L, event: E): Unit

  private[spark] def findListenersByClass[T <: L : ClassTag](): Seq[T] = {
    val c = implicitly[ClassTag[T]].runtimeClass
    listeners.asScala.filter(_.getClass == c).map(_.asInstanceOf[T]).toSeq
  }

}
