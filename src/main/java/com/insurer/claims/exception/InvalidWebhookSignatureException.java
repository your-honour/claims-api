package com.insurer.claims.exception;

/** The signature on an inbound payment webhook didn't match - reject it, don't trust the payload. */
public class InvalidWebhookSignatureException extends RuntimeException {
    public InvalidWebhookSignatureException(String message) {
        super(message);
    }
}
