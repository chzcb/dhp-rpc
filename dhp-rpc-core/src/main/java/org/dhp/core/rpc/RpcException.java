package org.dhp.core.rpc;

public class RpcException extends RuntimeException {
    RpcErrorCode code;
    public RpcException(RpcErrorCode code) {
        super(code.name());
        this.code = code;
    }
    
    public RpcErrorCode getCode() {
        return code;
    }
}
