package com.insurer.claims.client.dto;

/**
 * The Payment System's immediate (synchronous) response to a payment
 * request - just an acknowledgement that it was accepted for processing.
 * The actual outcome arrives later via the webhook (see
 * {@code PaymentWebhookController}) - payment processing is not instant,
 * so this call was never going to return the final status.
 */
public record PaymentRequestAcknowledgement(
        String providerRequestId,
        boolean accepted,
        String message
) {
}
