package com.insurer.claims.exception;

/** Thrown when code (or a caller) attempts an illegal claim state transition. */
public class InvalidClaimStateException extends RuntimeException {
    public InvalidClaimStateException(String message) {
        super(message);
    }
}
