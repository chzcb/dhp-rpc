package org.dhp.net.grizzly;

import lombok.extern.slf4j.Slf4j;
import org.dhp.common.rpc.Stream;
import org.dhp.common.utils.ProtostuffUtils;
import org.dhp.core.rpc.*;
import org.dhp.core.spring.FrameworkException;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.filterchain.*;
import org.glassfish.grizzly.nio.transport.TCPNIOConnection;
import org.glassfish.grizzly.nio.transport.TCPNIOTransport;
import org.glassfish.grizzly.nio.transport.TCPNIOTransportBuilder;
import org.glassfish.grizzly.strategies.SameThreadIOStrategy;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class GrizzlyRpcChannel extends RpcChannel {

    static TCPNIOTransport transport;

    TCPNIOConnection connection;

    AtomicInteger _ID = new AtomicInteger();
    
    static ClientStreamManager streamManager = new ClientStreamManager();
    
    @Override
    public void start() {
        if (transport == null) {
            TCPNIOTransportBuilder builder = TCPNIOTransportBuilder.newInstance();
            FilterChainBuilder fbuilder = FilterChainBuilder.stateless();
            fbuilder.add(new TransportFilter());
            fbuilder.add(new GrizzlyRpcMessageFilter());
            fbuilder.add(new BaseFilter(){
                @Override
                public NextAction handleClose(FilterChainContext ctx) throws IOException {
                    log.info("connection:{} closed!", ctx.getConnection());
                    return super.handleClose(ctx);
                }
    
                @Override
                public NextAction handleRead(FilterChainContext ctx) throws IOException {
                    GrizzlyMessage message = ctx.getMessage();
                    if(log.isDebugEnabled())
                        log.debug("recv: {}", message);
                    //close
                    if(message.getCommand().equals("close")){
                        Connection connection = ctx.getConnection();
                        readyToCloseConns.add(connection);
                    } else {
                        streamManager.handleMessage(message);
                    }
                    return super.handleRead(ctx);
                }
            });

            builder.setProcessor(fbuilder.build());

            builder.setTcpNoDelay(true);
            builder.setKeepAlive(true);
            builder.setLinger(0);
            builder.setIOStrategy(SameThreadIOStrategy.getInstance());

            this.transport = builder.build();

            try {
                this.transport.start();
            } catch (IOException e) {
                throw new FrameworkException("Grizzly Rpc Channel Start Failed");
            }
        }
        try {
            this.connect();
        } catch (TimeoutException e) {
            throw new FrameworkException("Grizzly Rpc Channel Connect Timeout");
        }
    }
    
    @Override
    public void register() {
        byte[] idBytes = ProtostuffUtils.serialize(Long.class, this.getId());
        sendMessage("register", idBytes);
    }
    
    public boolean connect() throws TimeoutException {
        if (connection != null && connection.isOpen() && connection.canWrite()) {
            return true;
        }
        try {
            log.info("connect to {}:{}", this.getHost(), this.getPort());
            connection = (TCPNIOConnection) this.transport.connect(this.getHost(), this.getPort()).get(this.getTimeout(), TimeUnit.MILLISECONDS);
            this.register();
            return true;
        } catch (InterruptedException e) {
            log.warn(e.getMessage(), e);
        } catch (ExecutionException e) {
            throw new RpcException(RpcErrorCode.UNREACHABLE_NODE);
        }
        return false;
    }

    private long activeTime = System.currentTimeMillis();
    
    protected GrizzlyMessage sendMessage(String command, byte[] body){
        synchronized (connection){
            while(readyToCloseConns.contains(connection)){
                try {
                    log.warn("waiting for switch channel");
                    connect();
                    Thread.sleep(100);
                } catch (InterruptedException | TimeoutException e) {
                    log.error("connect error: {}", e.getMessage(), e);
                } catch (RpcException e){
                    //下游节点找不到就继续连接
                    if(e.getCode() == RpcErrorCode.UNREACHABLE_NODE){
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException ex) {
                        }
                        continue;
                    }
                }
            }
        }
        GrizzlyMessage message = new GrizzlyMessage();
        message.setId(_ID.incrementAndGet());
        message.setCommand(command);
        message.setData(body);
        message.setStatus(MessageStatus.Sending);
        this.connection.write(message);
        return message;
    }

    public void ping() {
        if(connection.isOpen()){
            try {
                this.connect();
            } catch (TimeoutException e) {
                log.warn("reconnect failed");
            }
        }
        GrizzlyMessage message = sendMessage("ping", (System.currentTimeMillis()+"").getBytes());
        Stream<GrizzlyMessage> stream = new Stream<GrizzlyMessage>() {
            public void onCanceled() {
            }
            public void onNext(GrizzlyMessage value) {
            }
            public void onFailed(Throwable throwable) {
            }
            public void onCompleted() {
            }
        };
        streamManager.setStream(message, stream);
    }

    public Integer write(String name, byte[] argBody, Stream<Message> messageStream) {
        GrizzlyMessage message = sendMessage(name, argBody);
        streamManager.setStream(message, messageStream);
        return message.getId();
    }
}
