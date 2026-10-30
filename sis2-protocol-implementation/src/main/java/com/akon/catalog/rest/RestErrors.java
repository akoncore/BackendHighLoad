package com.akon.catalog.rest;

import com.akon.catalog.exception.CatalogException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;

@RestControllerAdvice
public class RestErrors {
    @ExceptionHandler(CatalogException.class)
    public ResponseEntity<?> business(CatalogException error) {
        int status = switch (error.kind()) {
            case INVALID -> 400;
            case NOT_FOUND -> 404;
            case KEY_REUSED -> 422;
            case VERSION_CONFLICT -> 412;
            case PRECONDITION_REQUIRED -> 428;
        };
        return response(status, error.kind().name().toLowerCase(java.util.Locale.ROOT), error.getMessage());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingRequestHeaderException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<?> invalid(Exception error) {
        return response(400, "invalid", "Проверьте JSON, параметры и обязательные заголовки");
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<?> database(DataAccessException error) {
        return ResponseEntity.status(503).header("Retry-After", "1")
                .body(body("unavailable", "База данных временно недоступна"));
    }

    private ResponseEntity<?> response(int status, String code, String message) {
        return ResponseEntity.status(status).body(body(code, message));
    }

    private Map<String, Object> body(String code, String message) {
        return Map.of("error", Map.of("code", code, "message", message));
    }
}
