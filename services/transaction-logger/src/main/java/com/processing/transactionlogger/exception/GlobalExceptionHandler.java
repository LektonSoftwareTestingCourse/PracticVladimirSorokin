package com.processing.transactionlogger.exception;

import com.processing.common.dto.ErrorResponse;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.transaction.CannotCreateTransactionException;

import java.time.Instant;
import java.util.stream.Collectors;

/**
 * Глобальный обработчик исключений.
 * Преобразует ошибки в единый формат {@link ErrorResponse} с полем {@code retryAfterMs}:
 * {@code "0"} — повторять не нужно, {@code "1000"} — retry через 1 сек (недоступность БД).
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String SERVICE_NAME = "transaction-logger";
    private static final String NO_RETRY = "0";
    private static final String DATABASE_RETRY = "1000";

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
        String message = exception.getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .sorted()
                .collect(Collectors.joining("; "));

        return ResponseEntity.badRequest().body(errorResponse(
                "Validation error",
                message,
                NO_RETRY
        ));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException exception) {
        String message = exception.getConstraintViolations().stream()
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                .collect(Collectors.joining("; "));

        return ResponseEntity.badRequest().body(errorResponse(
                "Validation error",
                message,
                NO_RETRY
        ));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleMalformedJson() {
        return ResponseEntity.badRequest().body(errorResponse(
                "Invalid request body",
                "Request body is malformed or contains unsupported values",
                NO_RETRY
        ));
    }

    @ExceptionHandler(TransactionConflictException.class)
    public ResponseEntity<ErrorResponse> handleTransactionConflict(TransactionConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(errorResponse(
                "Transaction conflict",
                exception.getMessage(),
                NO_RETRY
        ));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(DataIntegrityViolationException exception) {
        log.warn("Transaction logger data integrity violation", exception);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(errorResponse(
                "Data integrity violation",
                "Transaction data violates database constraints",
                NO_RETRY
        ));
    }

    @ExceptionHandler({DataAccessException.class, CannotCreateTransactionException.class})
    public ResponseEntity<ErrorResponse> handleDatabaseAccess(RuntimeException exception) {
        log.error("Transaction logger database access error", exception);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(errorResponse(
                "Database operation failed",
                "Transaction logger database is unavailable",
                DATABASE_RETRY
        ));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotAllowed(HttpRequestMethodNotSupportedException exception) {
        log.warn("Method not allowed", exception);
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(errorResponse(
                "Method not allowed",
                exception.getMessage(),
                NO_RETRY
        ));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NoResourceFoundException exception) {
        log.warn("Resource not found", exception);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse(
                "Not found",
                "Resource not found: " + exception.getResourcePath(),
                NO_RETRY
        ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleInternalError(Exception exception) {
        log.error("Unexpected transaction logger error", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse(
                "Internal server error",
                "Unexpected transaction logger error",
                NO_RETRY
        ));
    }

    private ErrorResponse errorResponse(String error, String message, String retryAfterMs) {
        return new ErrorResponse(
                error,
                message,
                Instant.now(),
                SERVICE_NAME,
                retryAfterMs
        );
    }
}
