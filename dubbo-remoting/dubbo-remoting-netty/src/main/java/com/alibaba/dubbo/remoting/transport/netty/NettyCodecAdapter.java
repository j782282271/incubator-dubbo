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
package com.alibaba.dubbo.remoting.transport.netty;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.remoting.Codec2;
import com.alibaba.dubbo.remoting.buffer.DynamicChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.*;
import org.jboss.netty.channel.ChannelHandler.Sharable;
import org.jboss.netty.handler.codec.oneone.OneToOneEncoder;

import java.io.IOException;

/**
 * NettyCodecAdapter.
 */
final class NettyCodecAdapter {

    private final ChannelHandler encoder = new InternalEncoder();

    private final ChannelHandler decoder = new InternalDecoder();

    private final Codec2 codec;

    private final URL url;

    private final int bufferSize;

    private final com.alibaba.dubbo.remoting.ChannelHandler handler;

    public NettyCodecAdapter(Codec2 codec, URL url, com.alibaba.dubbo.remoting.ChannelHandler handler) {
        this.codec = codec;
        this.url = url;
        this.handler = handler;
        int b = url.getPositiveParameter(Constants.BUFFER_KEY, Constants.DEFAULT_BUFFER_SIZE);
        this.bufferSize = b >= Constants.MIN_BUFFER_SIZE && b <= Constants.MAX_BUFFER_SIZE ? b : Constants.DEFAULT_BUFFER_SIZE;
    }

    public ChannelHandler getEncoder() {
        return encoder;
    }

    public ChannelHandler getDecoder() {
        return decoder;
    }

    @Sharable
    private class InternalEncoder extends OneToOneEncoder {

        /**
         * msg可能为request或response，codec.encode区分request或response，进行编码
         */
        @Override
        protected Object encode(ChannelHandlerContext ctx, Channel ch, Object msg) throws Exception {
            com.alibaba.dubbo.remoting.buffer.ChannelBuffer buffer =
                    com.alibaba.dubbo.remoting.buffer.ChannelBuffers.dynamicBuffer(1024);
            //此处url,返回结果一直是第一个接口暴露的url，原因见NettyServer的构造方法，即请求url为DemoServer，此处channel内的url可能为DemoTestService
            //NettyHandler的channelConnected方法中已经为channel创建了NettyChannel
            NettyChannel channel = NettyChannel.getOrAddChannel(ch, url, handler);
            try {
                codec.encode(channel, buffer, msg);
            } finally {
                NettyChannel.removeChannelIfDisconnected(ch);
            }
            return ChannelBuffers.wrappedBuffer(buffer.toByteBuffer());
        }
    }

    private class InternalDecoder extends SimpleChannelUpstreamHandler {

        private com.alibaba.dubbo.remoting.buffer.ChannelBuffer buffer =
                com.alibaba.dubbo.remoting.buffer.ChannelBuffers.EMPTY_BUFFER;

        /**
         * 当前方法会对buffer修改，只有当前方法只在单线程中调用才是线上安全的，所以我认为此方法为单线程调用
         * <p>
         * 接收的消息可能为request或response，codec.decode根据header中的flag，将消息decode为req或res,传给handler
         * 默认配置第一个handler为NettyHandler                         =>    MultiMessageHandler=》HeartbeatHandler=》AllChannelHandler=》DecodeHandler=》HeaderExchangeHandler=》dubboProtocol$handler
         * *******NettyHandler用于管理channel和集成netty 原生handler**|||**处理req res 创建server时载入的******************************|||处理req res由exchanger载入**********||||req才会走到此handler处理rpcInvocation（req中）返回rpcResult（response中）
         */
        @Override
        public void messageReceived(ChannelHandlerContext ctx, MessageEvent event) throws Exception {
            Object o = event.getMessage();
            if (!(o instanceof ChannelBuffer)) {
                ctx.sendUpstream(event);
                return;
            }

            ChannelBuffer input = (ChannelBuffer) o;
            int readable = input.readableBytes();
            if (readable <= 0) {
                return;
            }

            com.alibaba.dubbo.remoting.buffer.ChannelBuffer message;
            //this.buffer+input的所有字节流构建message
            //buffer在初次使用的时候是空的，但是之后就有可能不为空
            if (buffer.readable()) {
                //每次都复用buffer，并将之前遗留的buffer与input的内容汇总起来一并合成message存起来
                if (buffer instanceof DynamicChannelBuffer) {
                    //将input的所有byte放到buffer中
                    buffer.writeBytes(input.toByteBuffer());
                    message = buffer;
                } else {
                    //如果buffer不是动态的话要生成一个动态的buffer用来存放新的汇总信息，在后面finally代码中将message赋值给buffer
                    int size = buffer.readableBytes() + input.readableBytes();
                    message = com.alibaba.dubbo.remoting.buffer.ChannelBuffers.dynamicBuffer(
                            size > bufferSize ? size : bufferSize);
                    message.writeBytes(buffer, buffer.readableBytes());
                    message.writeBytes(input.toByteBuffer());
                }
            } else {
                //接到第一个dubbo包的话buffer还是空的，所以在这里会先把消息放到message中，在后面finally代码中将message赋值给buffer
                //也就是说这里相当于给buffer赋值了，以后buffer一直是这个值了
                message = com.alibaba.dubbo.remoting.buffer.ChannelBuffers.wrappedBuffer(
                        input.toByteBuffer());
            }

            //经过上面的逻辑处理之后message就是新的汇总信息（上次未处理的+本次新收到的）
            NettyChannel channel = NettyChannel.getOrAddChannel(ctx.getChannel(), url, handler);
            Object msg;
            int saveReaderIndex;

            try {
                // decode object.
                do {
                    saveReaderIndex = message.readerIndex();
                    try {
                        msg = codec.decode(channel, message);
                    } catch (IOException e) {
                        buffer = com.alibaba.dubbo.remoting.buffer.ChannelBuffers.EMPTY_BUFFER;
                        throw e;
                    }
                    //目前的信息解析不出来一个完成的dubbo，因此会跳出循环，直到下个数据包的到来
                    if (msg == Codec2.DecodeResult.NEED_MORE_INPUT) {
                        //恢复到之前的待读取字节位置
                        message.readerIndex(saveReaderIndex);
                        break;
                    } else {
                        //两次的readerIndex一样说明decode没有进行
                        if (saveReaderIndex == message.readerIndex()) {
                            buffer = com.alibaba.dubbo.remoting.buffer.ChannelBuffers.EMPTY_BUFFER;
                            throw new IOException("Decode without read data.");
                        }
                        //如果有读到解码的信息，将解码后的信息继续往下游发送
                        //这时候的msg就是对应的Request，Response或者心跳
                        if (msg != null) {
                            //交给handler处理
                            Channels.fireMessageReceived(ctx, msg, event.getRemoteAddress());
                        }
                    }
                } while (message.readable());
            } finally {
                if (message.readable()) {
                    //message中仍有未读的字节，说明上次读完一条消息后，又进来消息了，在这里把已读的消息丢弃掉
                    //将message赋值给buffer
                    message.discardReadBytes();
                    buffer = message;
                } else {
                    buffer = com.alibaba.dubbo.remoting.buffer.ChannelBuffers.EMPTY_BUFFER;
                }
                NettyChannel.removeChannelIfDisconnected(ctx.getChannel());
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) throws Exception {
            ctx.sendUpstream(e);
        }
    }
}