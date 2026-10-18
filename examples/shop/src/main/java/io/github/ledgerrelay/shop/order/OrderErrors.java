package io.github.ledgerrelay.shop.order;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Returns the reason for a rejection as an HTTP status and {@code {"error": "..."}}. */
@RestControllerAdvice
class OrderErrors {

    @ExceptionHandler(OrderNotFoundException.class)
    ResponseEntity<Map<String, String>> notFound(OrderNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(OrderConflictException.class)
    ResponseEntity<Map<String, String>> conflict(OrderConflictException e) {
        return error(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(IdempotencyMismatchException.class)
    ResponseEntity<Map<String, String>> mismatch(IdempotencyMismatchException e) {
        return error(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
    }

    @ExceptionHandler({IllegalArgumentException.class, MissingRequestHeaderException.class})
    ResponseEntity<Map<String, String>> badRequest(Exception e) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
