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

package org.apache.spark.network.netty

import java.nio.ByteBuffer

import scala.collection.JavaConverters._
import scala.language.existentials
import scala.reflect.ClassTag

import org.apache.spark.internal.Logging
import org.apache.spark.network.BlockDataManager
import org.apache.spark.network.buffer.{ManagedBuffer, NioManagedBuffer}
import org.apache.spark.network.client.{RpcResponseCallback, TransportClient}
import org.apache.spark.network.server.{OneForOneStreamManager, RpcHandler, StreamManager}
import org.apache.spark.network.shuffle.protocol.{BlockTransferMessage, OpenBlocks, StreamHandle, UploadBlock}
import org.apache.spark.serializer.Serializer
import org.apache.spark.storage.{BlockId, StorageLevel}

/**
 * Serves requests to open blocks by simply registering one chunk per block requested.
 * Handles opening and uploading arbitrary BlockManager blocks.
 *
 * Opened blocks are registered with the "one-for-one" strategy, meaning each Transport-layer Chunk
 * is equivalent to one Spark-level shuffle block.
 */
class NettyBlockRpcServer(
    appId: String,
    serializer: Serializer,
    blockManager: BlockDataManager)
  extends RpcHandler with Logging {

  private val streamManager = new OneForOneStreamManager()

  override def receive(
      client: TransportClient,
      rpcMessage: ByteBuffer,
      responseContext: RpcResponseCallback): Unit = {
    val message = BlockTransferMessage.Decoder.fromByteBuffer(rpcMessage)
    logTrace(s"Received request: $message")

    message match {
      case openBlocks: OpenBlocks =>
        // 取出OpenBlocks消息携带的BlockId数组，调用getBlockData获取数组中每一个BlockIid对应的Block
        val blocks: Seq[ManagedBuffer] =
          openBlocks.blockIds.map(BlockId.apply).map(blockManager.getBlockData)
        val streamId = streamManager.registerStream(appId, blocks.iterator.asJava)
        logTrace(s"Registered streamId $streamId with ${blocks.size} buffers")
        // 创建StreamHandle消息，并通过响应上下文回复客户端
        responseContext.onSuccess(new StreamHandle(streamId, blocks.size).toByteBuffer)

      case uploadBlock: UploadBlock =>
        // StorageLevel and ClassTag are serialized as bytes using our JavaSerializer.
        // 对于UploadBlock消息携带的元数据metadata进行反序列化，得到存储级别和类型标记
        val (level: StorageLevel, classTag: ClassTag[_]) = {
          serializer
            .newInstance()
            .deserialize(ByteBuffer.wrap(uploadBlock.metadata))
            .asInstanceOf[(StorageLevel, ClassTag[_])]
        }
        // 将UploadBlock携带的元数据metadata封装为NioManagedBuffer
        val data = new NioManagedBuffer(ByteBuffer.wrap(uploadBlock.blockData))
        val blockId = BlockId(uploadBlock.blockId)
        // 将Block存入本地存储体系
        blockManager.putBlockData(blockId, data, level, classTag)
        responseContext.onSuccess(ByteBuffer.allocate(0))
    }
  }

  override def getStreamManager(): StreamManager = streamManager
}
