package com.insurer.claims.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Body of the Payment System's webhook: {@code POST /claims/{id}/payment-callback}.
 *
 * <p>{@code eventId} is the field that makes the handler idempotent - the
 * Payment System (like any real provider - Stripe, PayGate, Adyen...) may
 * redeliver the same event if our 200 response is lost in transit, so the
 * same eventId arriving twice must not pay out or update the claim twice.
 * The actual authenticity check happens separately, against the
 * {@code X-Payment-Signature} header - see {@code PaymentWebhookController}.
 */
public record PaymentCallbackRequest(

        @NotBlank(message = "eventId is required")
        String eventId,

        @NotNull(message = "claimId is required")
        UUID claimId,

        @NotNull(message = "outcome is required")
        PaymentOutcome outcome,

        String providerPaymentReference
) {
}
