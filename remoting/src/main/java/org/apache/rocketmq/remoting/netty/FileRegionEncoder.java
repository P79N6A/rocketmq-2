/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.rocketmq.remoting.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.FileRegion;
import io.netty.handler.codec.MessageToByteEncoder;

import io.netty.handler.ssl.SslHandler;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;

/**
 * <p>
 *     By default, file region are directly transferred to socket channel which is known as zero copy. In case we need
 *     to encrypt transmission, data being sent should go through the {@link SslHandler}. This encoder ensures this
 *     process.
 * </p>
 * 默认情况下，文件区域直接传输到套接字通道，称为零拷贝。如果我们需要加密传输，发送的数据应该通过SslHandler。这个编码器确保了这个过程。
 */
//
public class FileRegionEncoder extends MessageToByteEncoder<FileRegion> {

    /**
     * Encode a message into a {@link io.netty.buffer.ByteBuf}. This method will be called for each written message that
     * can be handled by this encoder.将消息编码到ByteBuf中。对于可由此编码器处理的每个书面消息，将调用此方法。
     *
     * @param ctx the {@link io.netty.channel.ChannelHandlerContext} which this {@link
     * io.netty.handler.codec.MessageToByteEncoder} belongs to
     * @param msg the message to encode
     * @param out the {@link io.netty.buffer.ByteBuf} into which the encoded message will be written
     * @throws Exception is thrown if an error occurs
     */
    @Override
    protected void encode(ChannelHandlerContext ctx, FileRegion msg, final ByteBuf out) throws Exception {
        WritableByteChannel writableByteChannel = new WritableByteChannel() {
            @Override
            public int write(ByteBuffer src) throws IOException {
                out.writeBytes(src);
                return out.capacity();
            }

            @Override
            public boolean isOpen() {
                return true;
            }

            @Override
            public void close() throws IOException {
            }
        };

        long toTransfer = msg.count();

        while (true) {
            long transferred = msg.transfered();
            if (toTransfer - transferred <= 0) {
                break;
            }
            msg.transferTo(writableByteChannel, transferred);
        }
    }
}