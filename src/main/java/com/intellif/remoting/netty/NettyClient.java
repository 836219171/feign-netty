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
package com.intellif.remoting.netty;

import com.alibaba.fastjson.JSON;
import com.intellif.common.Constants;
import com.intellif.feign.transfer.TransferRequest;
import com.intellif.remoting.RemotingException;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.Date;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * NettyClient.
 */
public class NettyClient {

    private static final Logger logger = LoggerFactory.getLogger(NettyClient.class);

    private static final NioEventLoopGroup nioEventLoopGroup = new NioEventLoopGroup(Constants.DEFAULT_IO_THREADS, new DefaultThreadFactory("NettyClientWorker", true));

    private Bootstrap bootstrap;

    private volatile Channel channel; // volatile, please copy reference to use

    private NettyChannelHandler handler;

    private String host; //  [ip | hostname]

    private int port; // port

    public NettyClient(final String host, final int port, final NettyChannelHandler handler) throws Throwable {
        this.handler = handler;
        this.host = host;
        this.port = port;
        doOpen();
        doConnect();
    }

    protected void doOpen() throws Throwable {
        final NettyClientHandler nettyClientHandler = new NettyClientHandler(handler);
        bootstrap = new Bootstrap();
        bootstrap.group(nioEventLoopGroup)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                //.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, getTimeout())
                .channel(NioSocketChannel.class);

        //TODO 超时时间timeout可配
        bootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3000);

        bootstrap.handler(new ChannelInitializer() {

            @Override
            protected void initChannel(Channel ch) throws Exception {
                ch.pipeline()//.addLast("logging",new LoggingHandler(LogLevel.INFO))//for debug
                        .addLast("decoder", new LengthFieldBasedFrameDecoder(1024 * 10, 0, 8, 0, 8))
                        .addLast("encoder", new LengthFieldPrepender(8))
                        .addLast("stringDecoder", new StringDecoder())
                        .addLast("stringEncoder", new StringEncoder())
                        .addLast("handler", nettyClientHandler);
            }
        });
    }

    protected void doConnect() throws RemotingException {
        long start = System.currentTimeMillis();
        ChannelFuture future = bootstrap.connect(new InetSocketAddress(host, port));
        try {
            boolean ret = future.awaitUninterruptibly(3000, TimeUnit.MILLISECONDS);

            if (ret && future.isSuccess()) {
                Channel newChannel = future.channel();
                try {
                    // Close old channel
                    Channel oldChannel = NettyClient.this.channel; // copy reference
                    if (oldChannel != null) {
                        try {
                            if (logger.isInfoEnabled()) {
                                logger.info("Close old netty channel " + oldChannel + " on create new netty channel " + newChannel);
                            }
                            oldChannel.close();
                        } finally {
                            // doing nothing..
                        }
                    }
                } finally {
                    NettyClient.this.channel = newChannel;
                    logger.info("Netty client connected to server: " + NetUtils.toAddressString((InetSocketAddress) channel.remoteAddress()));
                }
            } else if (future.cause() != null) {
                throw new RemotingException(this.channel, "client(url: ) failed to connect to server "
                        + host + ":" + port + " , error message is:" + future.cause().getMessage(), future.cause());
            } else {
                throw new RemotingException(this.channel, "client(url: ) failed to connect to server "
                        + host + ":" + port + " client-side timeout "
                        + getConnectTimeout() + "ms (elapsed: " + (System.currentTimeMillis() - start) + "ms) from netty client "
                        + NetUtils.getLocalHost());
            }
        } finally {
            if (!isConnected()) {
                //future.cancel(true);
            }
        }
    }

    public void send(Object message, boolean sent) throws RemotingException {
        if (!isConnected()) {
            System.out.println("reconnect");
            doConnect();
        }
        Channel channel = getChannel();
        if (channel == null || !channel.isActive()) {
            throw new RemotingException(this.channel, "message can not send, because channel is closed . url:[" + host + ":" + port + "]");
        }
        channel.writeAndFlush(message);
    }

    public Object sendSync(TransferRequest request, long timeout, TimeUnit unit) throws RemotingException {
        Date start = new Date();
//        System.out.printf("start request %s : => %d \n", request.getUuid(), new Date().getTime());
        if (!isConnected()) {
            System.out.println("reconnect");
            doConnect();
        }
        Channel channel = getChannel();
        CountDownLatch latch = new CountDownLatch(1);
        handler.setLatch(request.getUuid(), latch); //把latch设置到handler里面，方便在handler中获取到结果时通知这边停止等待
        //这里顺序很重要，一定要先把latch放在handler中，然后再发送请求给服务端，如果反过来可能会导致服务端响应太快，还没有吧latch放进map，就接收到响应，此时是拿不到这个latch的
        channel.writeAndFlush(JSON.toJSONString(request));
        try {
//            System.out.printf("begin await request %s : => %d \n", request.getUuid(), new Date().getTime());
            if (latch.await(timeout, unit)) { //在超时之前成功返回
//                System.out.printf("get request return %s : => %d \n", request.getUuid(), new Date().getTime());
                System.out.println("get result " + (new Date().getTime() - start.getTime()));
                return handler.getResult(request.getUuid());
            }

        } catch (InterruptedException e) {
            logger.error(e.getMessage());
            throw new RemotingException(channel, "The request is interrupted!");
        }
        //请求超时，直接抛异常
        throw new RemotingException(channel, "The remote service call timeout!");
    }

    public boolean isConnected() {
        Channel channel = this.channel;
        return channel != null && channel.isActive();
    }


    public Channel getChannel() {
        return this.channel;
    }

    private int getConnectTimeout() {
        return 3000;
    }

}
