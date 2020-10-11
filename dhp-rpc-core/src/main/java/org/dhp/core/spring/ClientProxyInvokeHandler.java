package org.dhp.core.spring;

import lombok.extern.slf4j.Slf4j;
import org.dhp.common.annotation.DMethod;
import org.dhp.common.annotation.DService;
import org.dhp.common.rpc.Stream;
import org.dhp.common.rpc.StreamFuture;
import org.dhp.common.utils.ProtostuffUtils;
import org.dhp.core.rpc.*;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;

/**
 * 对于invoke的方法，应该需要统一标准，所有入参都应该继承RpcRequest或者增加Stream流入参，用于多个结果的返回，所有出参都应该集成RpcResponse或者是FutureResponse
 * @author zhangcb
 */
@Slf4j
public class ClientProxyInvokeHandler implements IClientInvokeHandler, ImportBeanDefinitionRegistrar, BeanFactoryAware, InitializingBean {

    RpcChannelPool channelPool;

    protected Set<Method> excludeMethods = ConcurrentHashMap.newKeySet();

    protected Map<Method, Command> cacheCommands = new ConcurrentHashMap<>();

    protected ExecutorService pool = Executors.newCachedThreadPool(new ThreadFactory() {
        private int i = 1;

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r);
            t.setName("ClientProxy_" + (i++));
            t.setDaemon(true);
            return t;
        }
    });

    protected boolean isExcludeMethod(Method method) {
        if (excludeMethods.contains(method)) {
            return true;
        }
        return false;
    }

    protected Command getCommand(Method method) {
        if (cacheCommands.containsKey(method)) {
            return cacheCommands.get(method);
        }
        Command command = new Command();
        command.setCls(method.getDeclaringClass());
        command.setMethod(method);

        DService service = command.getCls().getAnnotation(DService.class);
        command.setNodeName(service.node());

        String methodName = method.getName();
        String className = method.getDeclaringClass().getName();

        String commandName = className + ":" + methodName;
        DMethod dm = method.getAnnotation(DMethod.class);
        //if defined Dmethod annotation, use dmethod to send
        if (dm != null && !StringUtils.isEmpty(dm.command())) {
            commandName = dm.command();
        }
        command.setName(commandName);
        cacheCommands.put(method, command);
        return command;
    }



    @Override
    public Object invoke(Object o, Method method, Object[] args) throws Throwable {
        if (isExcludeMethod(method)) {
            return null;
        }
        if (method.getName().equalsIgnoreCase("toString")) {
            return method.getDeclaringClass().getName();
        }
        if (method.getName().equalsIgnoreCase("equals")) {
            return true;
        }
        Command command = getCommand(method);
        if (command == null) {
            throw new RpcException(RpcErrorCode.COMMAND_NOT_FOUND);
        }

        Type returnType = method.getReturnType();
        Type[] paramTypes = method.getParameterTypes();

        byte[] argBody;
        MethodType methodType = MethodType.Default;
        Stream argStream = null;
        FutureImpl future = null;
        //入参为1个
        if (args.length == 1) {
            future = new FutureImpl();
            if (StreamFuture.class.isAssignableFrom((Class) returnType)) {
                methodType = MethodType.Future;
            } else if(List.class.isAssignableFrom((Class)returnType)){
                methodType = MethodType.List;
            } else {
                methodType = MethodType.Default;
            }
            argBody = ProtostuffUtils.serialize((Class) paramTypes[0], args[0]);
        } else if (args.length == 2) {//如果入参是2个，那么就说明，其中一个是入参对象，另外一个是Stream流对象
            if (paramTypes[0] instanceof Stream) {
                argBody = ProtostuffUtils.serialize((Class) paramTypes[1], args[1]);
                argStream = (Stream) args[0];
            } else {
                argBody = ProtostuffUtils.serialize((Class) paramTypes[0], args[0]);
                argStream = (Stream) args[1];
            }
            methodType = MethodType.Stream;
        } else {
            throw new RpcException(RpcErrorCode.ILLEGAL_PARAMETER_DEFINITION);
        }
        MethodType finalMethodType = methodType;
        Stream finalArgStream = argStream;
        FutureImpl finalFuture = future;
        MethodType finalMethodType1 = methodType;
        Stream<Message> stream = new Stream<Message>() {
            @Override
            public void onCanceled() {
                if (finalMethodType == MethodType.Stream) {
                    finalArgStream.onCanceled();
                } else {
                    finalFuture.cancel(false);
                }
            }
            @Override
            public void onNext(Message value) {
                Object ret = dealResult(finalMethodType1, method, value.getData());
                if (finalMethodType == MethodType.Stream) {
                    finalArgStream.onNext(ret);
                } else {
                    finalFuture.result(ret);
                }
            }
            @Override
            public void onFailed(Throwable throwable) {
                if (finalMethodType == MethodType.Stream) {
                    finalArgStream.onFailed(throwable);
                } else {
                    finalFuture.failure(throwable);
                }
            }

            @Override
            public void onCompleted() {
                if (finalMethodType == MethodType.Stream) {
                    finalArgStream.onCompleted();
                } else {
                    finalFuture.result(null);
                }
            }
        };
        //发送
        Integer messageId = sendMessage(command, argBody, stream);
        if(messageId == null){
            throw new RpcException(RpcErrorCode.SEND_MESSAGE_FAILED);
        }
        if (finalMethodType == MethodType.Stream) {
            return null;
        } else if (finalMethodType == MethodType.Future) {
            return future;
        } else if (future != null) {
            try {
                return future.get(15000, TimeUnit.MILLISECONDS);
            } catch (ExecutionException e){
                throw e.getCause();
            } catch (TimeoutException e){
                throw new RpcException(RpcErrorCode.TIMEOUT);
            }
        }
        else {
            return null;
        }
    }

    private Object dealResult(MethodType methodType, Method method, byte[] result) {
        try {
            if (methodType == MethodType.Default) {
                return ProtostuffUtils.deserialize(result, (Class) method.getReturnType());
            } else if(methodType == MethodType.List) {
                ParameterizedType type = (ParameterizedType) method.getGenericReturnType();
                Class clas = (Class) type.getActualTypeArguments()[0];
                return ProtostuffUtils.deserializeList(result, clas);
            } else if (methodType == MethodType.Future) {
                ParameterizedType type = (ParameterizedType) method.getGenericReturnType();
                Class clas = (Class) type.getActualTypeArguments()[0];
                return ProtostuffUtils.deserialize(result, clas);
            } else if (methodType == MethodType.Stream) {
                Type[] paramTypes = method.getGenericParameterTypes();
                if(paramTypes[0] instanceof ParameterizedType){
                    ParameterizedType pType = (ParameterizedType)paramTypes[0];
                    return ProtostuffUtils.deserialize(result, (Class) pType.getActualTypeArguments()[0]);
                } else {
                    ParameterizedType pType = (ParameterizedType)paramTypes[1];
                    return ProtostuffUtils.deserialize(result, (Class) pType.getActualTypeArguments()[0]);
                }
            }
        } catch (Throwable e) {
            log.error(e.getMessage(), e);
        }
        return null;
    }
    
    /**
     * 发送消息，并返回消息编号
     *
     * @param command
     * @param argBody
     * @return
     */
    protected Integer sendMessage(Command command, byte[] argBody, Stream<Message> stream) {
        RpcChannel channel = channelPool.getChannel(command);
        return channel.write(command.getName(), argBody, stream);
    }


    BeanFactory beanFactory;

    @Override
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        this.beanFactory = beanFactory;
    }
    
    @Override
    public void afterPropertiesSet() throws Exception {
        if(channelPool == null){
            channelPool = beanFactory.getBean(RpcChannelPool.class);
        }
    }
}
