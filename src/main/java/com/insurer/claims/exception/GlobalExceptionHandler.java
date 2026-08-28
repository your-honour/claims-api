package com.insurer.claims.exception;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * Deliberately thin: every other exception in this app is caught and turned
 * into a {@link ApiError} response directly in the controller method that
 * can throw it (see {@code ClaimsController}, {@code
 * PaymentWebhookController}), not centralized here.
 *
 * <p>{@link MethodArgumentNotValidException} is the one case that can't
 * follow that pattern - {@code @Valid} throws it from Spring's argument
 * resolver <em>before</em> the controller method body runs, so there's no
 * method-body try/catch that could ever see it.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(ApiError.of(message));
    }
}
