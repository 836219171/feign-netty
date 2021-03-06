package com.intellif.remoting.netty;

import io.netty.channel.*;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author inori
 * @create 2019-07-05 13:45
 */
@ChannelHandler.Sharable
public class NettyServerHandler extends ChannelDuplexHandler {

    /**
     * logger
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(NettyServerHandler.class);


    private final Map<String, Channel> channels = new ConcurrentHashMap<>(); // <ip:port, channel>

    private final NettyChannelHandler handler;

    public NettyServerHandler(NettyChannelHandler handler) {
        this.handler = handler;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        ctx.fireChannelActive();
        Channel channel = ctx.channel();
        try {
            if (channel != null) {
                channels.put(NetUtils.toAddressString((InetSocketAddress) ctx.channel().remoteAddress()), ctx.channel());
            }
            handler.connected(channel);
        } finally {
            removeChannelIfDisconnected(channel);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        Channel channel = ctx.channel();
        try {
            channels.remove(NetUtils.toAddressString((InetSocketAddress) ctx.channel().remoteAddress()));
            handler.disconnected(channel);
        } finally {
            removeChannelIfDisconnected(channel);
        }
    }


    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        Channel channel = ctx.channel();
        try {
            handler.received(channel, msg);
        } finally {
            removeChannelIfDisconnected(channel);
        }
    }


    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        super.write(ctx, msg, promise);
        Channel channel = ctx.channel();
        try {
            handler.sent(channel, msg);
        } finally {
            removeChannelIfDisconnected(channel);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause)
            throws Exception {
        Channel channel = ctx.channel();
        try {
            handler.caught(channel, cause);
        } finally {
            removeChannelIfDisconnected(channel);
        }
        //捕获到异常不再向下传递.
//        super.exceptionCaught(ctx, cause);
    }

    public Map<String, Channel> getChannels() {
        return channels;
    }


    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            IdleStateEvent idle = (IdleStateEvent) evt;
            switch (idle.state()) {
                case READER_IDLE:
                    ctx.writeAndFlush("h"); //发送心跳
            }
        } else if (evt instanceof ChannelInputShutdownEvent) {
            Channel channel = ctx.channel();
            LOGGER.error("The remote client {} force to closed connection", NetUtils.toAddressString((InetSocketAddress) channel.remoteAddress()));
            channel.close();//远程主机强制关闭连接
        }
    }

    public void removeChannelIfDisconnected(Channel channel) {
        if (channel != null && !channel.isActive()) {
            channels.remove(NetUtils.toAddressString((InetSocketAddress) channel.remoteAddress()));
        }
    }

}