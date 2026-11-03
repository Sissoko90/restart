package com.abdatytch.user_service.exception;

import com.abdatytch.user_service.constants.ExceptionConstants;
import com.abdatytch.user_service.dto.response.ErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Global Exception Handler réactif pour WebFlux
 * Migré de Servlet vers WebFlux
 * PRÉSERVE TOUTE LA LOGIQUE de gestion d'erreurs
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {
    
    @ExceptionHandler(ResourceNotFoundException.class)
    public Mono<ResponseEntity<ErrorResponse>> handleResourceNotFoundException(
            ResourceNotFoundException ex, 
            ServerWebExchange exchange) {
        
        log.error(ExceptionConstants.LOG_ERROR_RESOURCE_NOT_FOUND, ex.getMessage());
        
        ErrorResponse error = new ErrorResponse(
            HttpStatus.NOT_FOUND.value(),
            ExceptionConstants.ERROR_NOT_FOUND,
            ex.getMessage(),
            exchange.getRequest().getPath().value()
        );
        
        return Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND).body(error));
    }
    
    @ExceptionHandler(ForbiddenException.class)
    public Mono<ResponseEntity<ErrorResponse>> handleForbiddenException(
            ForbiddenException ex, 
            ServerWebExchange exchange) {
        
        log.error(ExceptionConstants.LOG_ERROR_ACCESS_FORBIDDEN, ex.getMessage());
        
        ErrorResponse error = new ErrorResponse(
            HttpStatus.FORBIDDEN.value(),
            ExceptionConstants.ERROR_FORBIDDEN,
            ex.getMessage(),
            exchange.getRequest().getPath().value()
        );
        
        return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN).body(error));
    }
    
    @ExceptionHandler(ResourceAlreadyExistsException.class)
    public Mono<ResponseEntity<ErrorResponse>> handleResourceAlreadyExistsException(
            ResourceAlreadyExistsException ex, 
            ServerWebExchange exchange) {
        
        log.error(ExceptionConstants.LOG_ERROR_RESOURCE_ALREADY_EXISTS, ex.getMessage());
        
        ErrorResponse error = new ErrorResponse(
            HttpStatus.CONFLICT.value(),
            ExceptionConstants.ERROR_CONFLICT,
            ex.getMessage(),
            exchange.getRequest().getPath().value()
        );
        
        return Mono.just(ResponseEntity.status(HttpStatus.CONFLICT).body(error));
    }
    
    @ExceptionHandler(InvalidTokenException.class)
    public Mono<ResponseEntity<ErrorResponse>> handleInvalidTokenException(
            InvalidTokenException ex, 
            ServerWebExchange exchange) {
        
        log.error(ExceptionConstants.LOG_ERROR_INVALID_TOKEN, ex.getMessage());
        
        ErrorResponse error = new ErrorResponse(
            HttpStatus.UNAUTHORIZED.value(),
            ExceptionConstants.ERROR_UNAUTHORIZED,
            ex.getMessage(),
            exchange.getRequest().getPath().value()
        );
        
        return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error));
    }
    
    @ExceptionHandler(AccessDeniedException.class)
    public Mono<ResponseEntity<ErrorResponse>> handleAccessDeniedException(
            AccessDeniedException ex, 
            ServerWebExchange exchange) {
        
        log.error(ExceptionConstants.LOG_ERROR_ACCESS_DENIED, ex.getMessage());
        
        ErrorResponse error = new ErrorResponse(
            HttpStatus.FORBIDDEN.value(),
            ExceptionConstants.ERROR_FORBIDDEN,
            ExceptionConstants.MSG_NO_PERMISSION,
            exchange.getRequest().getPath().value()
        );
        
        return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN).body(error));
    }
    
    @ExceptionHandler(WebExchangeBindException.class)
    public Mono<ResponseEntity<ErrorResponse>> handleValidationException(
            WebExchangeBindException ex, 
            ServerWebExchange exchange) {
        
        log.error(ExceptionConstants.LOG_ERROR_VALIDATION, ex.getMessage());
        
        List<String> details = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(error -> error.getField() + ExceptionConstants.FIELD_SEPARATOR + error.getDefaultMessage())
                .collect(Collectors.toList());
        
        ErrorResponse error = new ErrorResponse(
            HttpStatus.BAD_REQUEST.value(),
            ExceptionConstants.ERROR_VALIDATION_FAILED,
            ExceptionConstants.MSG_INVALID_REQUEST_PARAMS,
            exchange.getRequest().getPath().value(),
            details
        );
        
        return Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error));
    }
    
    @ExceptionHandler(IllegalArgumentException.class)
    public Mono<ResponseEntity<ErrorResponse>> handleIllegalArgumentException(
            IllegalArgumentException ex, 
            ServerWebExchange exchange) {
        
        log.error(ExceptionConstants.LOG_ERROR_ILLEGAL_ARGUMENT, ex.getMessage());
        
        ErrorResponse error = new ErrorResponse(
            HttpStatus.BAD_REQUEST.value(),
            ExceptionConstants.ERROR_BAD_REQUEST,
            ex.getMessage(),
            exchange.getRequest().getPath().value()
        );
        
        return Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error));
    }
    
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public Mono<ResponseEntity<ErrorResponse>> handleMultipartException(
            org.springframework.web.multipart.MaxUploadSizeExceededException ex, 
            ServerWebExchange exchange) {
        
        log.error(ExceptionConstants.LOG_ERROR_MULTIPART_PARSING, ex.getMessage(), ex);
        
        ErrorResponse error = new ErrorResponse(
            HttpStatus.BAD_REQUEST.value(),
            ExceptionConstants.ERROR_MULTIPART,
            String.format(ExceptionConstants.MSG_MULTIPART_ERROR, ex.getMessage()),
            exchange.getRequest().getPath().value()
        );
        
        return Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error));
    }
    
    @ExceptionHandler(FileStorageException.class)
    public Mono<ResponseEntity<ErrorResponse>> handleFileStorageException(
            FileStorageException ex, 
            ServerWebExchange exchange) {
        
        log.error(ExceptionConstants.LOG_ERROR_FILE_STORAGE, ex.getMessage());
        
        ErrorResponse error = new ErrorResponse(
            HttpStatus.BAD_REQUEST.value(),
            ExceptionConstants.ERROR_FILE_STORAGE,
            ex.getMessage(),
            exchange.getRequest().getPath().value()
        );
        
        return Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error));
    }
    
    @ExceptionHandler(org.springframework.web.server.ServerWebInputException.class)
    public Mono<ResponseEntity<ErrorResponse>> handleMissingParameterException(
            org.springframework.web.server.ServerWebInputException ex, 
            ServerWebExchange exchange) {
        
        log.error(ExceptionConstants.LOG_ERROR_MISSING_PARAMETER, ex.getMessage());
        
        ErrorResponse error = new ErrorResponse(
            HttpStatus.BAD_REQUEST.value(),
            ExceptionConstants.ERROR_MISSING_PARAMETER,
            ex.getReason() != null ? ex.getReason() : "Missing required parameter",
            exchange.getRequest().getPath().value()
        );
        
        return Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error));
    }
    
    @ExceptionHandler(org.springframework.web.server.MethodNotAllowedException.class)
    public Mono<ResponseEntity<ErrorResponse>> handleMethodNotAllowedException(
            org.springframework.web.server.MethodNotAllowedException ex, 
            ServerWebExchange exchange) {
        
        log.error(ExceptionConstants.LOG_ERROR_METHOD_NOT_SUPPORTED, ex.getMessage());
        
        String supportedMethods = ex.getSupportedMethods() != null 
            ? String.join(ExceptionConstants.METHOD_SEPARATOR, ex.getSupportedMethods().stream().map(Object::toString).toList())
            : ExceptionConstants.MSG_SUPPORTED_METHODS_NA;
        
        ErrorResponse error = new ErrorResponse(
            HttpStatus.METHOD_NOT_ALLOWED.value(),
            ExceptionConstants.ERROR_METHOD_NOT_ALLOWED,
            String.format(ExceptionConstants.MSG_METHOD_NOT_SUPPORTED, 
                ex.getHttpMethod(), supportedMethods),
            exchange.getRequest().getPath().value()
        );
        
        return Mono.just(ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(error));
    }
    
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public Mono<ResponseEntity<ErrorResponse>> handleDataIntegrityViolationException(
            org.springframework.dao.DataIntegrityViolationException ex, 
            ServerWebExchange exchange) {
        
        log.error(ExceptionConstants.LOG_ERROR_DATA_INTEGRITY, ex.getMessage());
        
        String message = ExceptionConstants.MSG_DATA_INTEGRITY_DEFAULT;
        if (ex.getMessage() != null && ex.getMessage().contains(ExceptionConstants.KEYWORD_CONSTRAINT)) {
            message = ExceptionConstants.MSG_DATA_INTEGRITY_CONSTRAINT;
        }
        
        ErrorResponse error = new ErrorResponse(
            HttpStatus.CONFLICT.value(),
            ExceptionConstants.ERROR_DATA_INTEGRITY,
            message,
            exchange.getRequest().getPath().value()
        );
        
        return Mono.just(ResponseEntity.status(HttpStatus.CONFLICT).body(error));
    }
    
    @ExceptionHandler(UnsupportedOperationException.class)
    public Mono<ResponseEntity<ErrorResponse>> handleUnsupportedOperationException(
            UnsupportedOperationException ex, 
            ServerWebExchange exchange) {
        
        log.error(ExceptionConstants.LOG_ERROR_UNSUPPORTED_OPERATION, ex.getMessage());
        
        ErrorResponse error = new ErrorResponse(
            HttpStatus.NOT_IMPLEMENTED.value(),
            ExceptionConstants.ERROR_NOT_IMPLEMENTED,
            ex.getMessage() != null ? ex.getMessage() : ExceptionConstants.MSG_UNSUPPORTED_OPERATION_DEFAULT,
            exchange.getRequest().getPath().value()
        );
        
        return Mono.just(ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(error));
    }
    
    @ExceptionHandler(Exception.class)
    public Mono<ResponseEntity<ErrorResponse>> handleGenericException(
            Exception ex, 
            ServerWebExchange exchange) {
        
        log.error(ExceptionConstants.LOG_ERROR_UNEXPECTED, ex.getClass().getSimpleName(), ex.getMessage(), ex);
        
        String detailedMessage = String.format(ExceptionConstants.MSG_UNEXPECTED_ERROR, 
            ex.getClass().getSimpleName(), 
            ex.getMessage() != null ? ex.getMessage() : ExceptionConstants.MSG_NO_DETAILS);
        
        ErrorResponse error = new ErrorResponse(
            HttpStatus.INTERNAL_SERVER_ERROR.value(),
            ExceptionConstants.ERROR_INTERNAL_SERVER,
            detailedMessage,
            exchange.getRequest().getPath().value()
        );
        
        return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error));
    }
}
