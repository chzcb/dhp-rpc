package org.dhp.net.netty4;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import lombok.extern.slf4j.Slf4j;
import org.dhp.common.rpc.Stream;
import org.dhp.common.utils.ProtostuffUtils;
import org.dhp.core.rpc.*;

import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class NettyRpcChannel extends RpcChannel {
    
    final static EventLoopGroup group = new NioEventLoopGroup();
    
    static Bootstrap b;
    
    AtomicInteger _ID = new AtomicInteger();
    
    Channel channel;
    
    ClientStreamManager streamManager;
    
    public void start() {
        streamManager = new ClientStreamManager();
        if (b == null) {
            b = new Bootstrap();
            b.option(ChannelOption.SO_KEEPALIVE, true);
            b.option(ChannelOption.TCP_NODELAY, true);
            b.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000);
    
            b.group(group).channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        public void initChannel(SocketChannel ch) throws Exception {
                            ChannelPipeline pipeline = ch.pipeline();
                            pipeline.addLast(new RpcMessageEncoder());
                            pipeline.addLast(new RpcMessageDecoder());
                            pipeline.addLast(new ChannelInboundHandlerAdapter() {
                                @Override
                                public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                                    NettyMessage message = (NettyMessage) msg;
                                    //close
                                    if (message.getCommand().equals("close")) {
                                        Channel channel = ctx.channel();
                                        readyToCloseConns.add(channel);
                                    } else {
                                        streamManager.handleMessage(message);
                                    }
                                }
                            }); //客户端处理类
                        }
                    });
        }
        connect();
    }
    
    @Override
    public void register() {
        byte[] idBytes = ProtostuffUtils.serialize(Long.class, this.getId());
        sendMessage("register", idBytes);
    }
    
    public boolean connect() {
        if (channel == null || !channel.isOpen()) {
            ChannelFuture future = null;
            try {
                log.info("connect to {}:{}", this.getHost(), this.getPort());
                future = b.connect(this.getHost(), this.getPort()).sync();
            } catch (Exception e) {
                throw new RpcException(RpcErrorCode.UNREACHABLE_NODE);
            }
            this.channel = future.channel();
            this.register();
        }
        return true;
    }
    
    private long activeTime = System.currentTimeMillis();
    
    public NettyMessage sendMessage(String command, byte[] body) {
        synchronized (channel) {
            while (readyToCloseConns.contains(channel)) {
                try {
                    log.warn("waiting for switch channel");
                    connect();
                } catch (RpcException e) {
                    if(e.getCode() == RpcErrorCode.UNREACHABLE_NODE){
                        continue;
                    }
                } finally {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                    }
                }
            }
        }
        
        NettyMessage message = new NettyMessage();
        message.setId(_ID.incrementAndGet());
        message.setCommand(command);
        message.setData(body);
        message.setStatus(MessageStatus.Sending);
        this.channel.writeAndFlush(message);
        return message;
    }
    
    public void ping() {
        //超过30秒没有更新，那么久重连
        if (System.currentTimeMillis() - activeTime > 30000) {
            connect();
        }
        Stream<NettyMessage> stream = new Stream<NettyMessage>() {
            public void onCanceled() {
            }
            
            public void onNext(NettyMessage value) {
                activeTime = System.currentTimeMillis();
                log.info("pong " + new String(value.getData()));
            }
            
            public void onFailed(Throwable throwable) {
            }
            
            public void onCompleted() {
            }
        };
        NettyMessage message = sendMessage("ping", (System.currentTimeMillis() + "").getBytes());
        streamManager.setStream(message, stream);
    }
    
    @Override
    public Integer write(String name, byte[] argBody, Stream<Message> messageStream) {
        NettyMessage message = sendMessage(name, argBody);
        streamManager.setStream(message, messageStream);
        return message.getId();
    }
    
}
