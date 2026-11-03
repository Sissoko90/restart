package com.abdatytch.auth_service.exception;

import com.abdatytch.auth_service.constants.ErrorCode;
import lombok.Getter;

@Getter
public class AuthException extends RuntimeException {
    
    private final ErrorCode errorCode;
    private final Object[] args;
    
    public AuthException(String message) {
        super(message);
        this.errorCode = null;
        this.args = new Object[0];
    }
    
    public AuthException(ErrorCode errorCode, Object... args) {
        super(errorCode.getFormattedMessage(args));
        this.errorCode = errorCode;
        this.args = args;
    }
    
    public AuthException(ErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
        this.args = new Object[0];
    }
    
    public AuthException(ErrorCode errorCode, Throwable cause, Object... args) {
        super(errorCode.getFormattedMessage(args), cause);
        this.errorCode = errorCode;
        this.args = args;
    }
}
