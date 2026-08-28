package com.insurer.claims.client;

import com.insurer.claims.client.dto.PaymentRequestAcknowledgement;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Adapter for the Payment System (third party). {@link #requestPayment} only
 * returns an acknowledgement that the request was accepted for processing -
 * the actual outcome arrives later via the signed webhook handled by
 * {@code PaymentWebhookController}.
 */
public interface PaymentClient {

    PaymentRequestAcknowledgement requestPayment(UUID claimId, BigDecimal amount, String payeeClientId);
}
