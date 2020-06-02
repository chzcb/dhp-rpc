package org.dhp.core.rpc;

import lombok.extern.slf4j.Slf4j;
import org.dhp.common.rpc.Stream;
import org.dhp.common.utils.ProtostuffUtils;
import org.dhp.core.spring.FrameworkException;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端的StreamManager，用于管理所有的流
 * @author zhangcb
 */
@Slf4j
public class ClientStreamManager {
    
    static Map<Integer, Stream> handlerMap = new ConcurrentHashMap<>();
    static Map<Integer, Message> streamMessages = new ConcurrentHashMap<>();
    
    public Throwable dealThrowable(Message message) {
        RpcFailedResponse failedResponse = ProtostuffUtils.deserialize(message.getData(), RpcFailedResponse.class);
        log.warn("throwable: {},{}", failedResponse.getClsName(), failedResponse.getMessage());
        if(log.isDebugEnabled())
            log.debug("content: {}", failedResponse.getContent());
        return new UnknowFailedException(failedResponse.getClsName(), failedResponse.getMessage(), failedResponse.getContent()).getCause();
    }
    
    /**
     * 设置流
     * @param message
     * @param stream
     */
    public void setStream(Message message, Stream stream) {
        handlerMap.put(message.getId(), stream);
        streamMessages.put(message.getId(), message);
    }
    
    /**
     * Deal message from rpc server
     * @param message
     */
    public void handleMessage(Message message){
        if (!handlerMap.containsKey(message.getId())) {
            return;
        }
        Stream handler = handlerMap.get(message.getId());
        MessageStatus status = message.getStatus();
        switch (status) {
            case Canceled:
                handler.onCanceled();
                break;
            case Completed:
                try {
                    handler.onNext(message);
                    handler.onCompleted();
                } catch (Throwable e) {
                    log.warn(e.getMessage(), e);
                } finally {
                    handlerMap.remove(message.getId());
                    streamMessages.remove(message.getId());
                }
                break;
            case Updating:
                handler.onNext(message);
                break;
            case Failed:
                handler.onFailed(dealThrowable(message));
                break;
            default:
                throw new FrameworkException("Sending MessageStatus can't response");
        }
    }
    
    
}
