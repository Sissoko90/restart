package com.abdatytch.user_service.exception;

/**
 * Exception levée lorsqu'un utilisateur n'a pas les permissions nécessaires
 */
public class ForbiddenException extends RuntimeException {
    
    public ForbiddenException(String message) {
        super(message);
    }
}
