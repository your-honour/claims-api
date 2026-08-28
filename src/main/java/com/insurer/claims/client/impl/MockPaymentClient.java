package com.insurer.claims.client.impl;

import com.insurer.claims.client.PaymentClient;
import com.insurer.claims.client.dto.PaymentRequestAcknowledgement;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Stands in for the real (third-party) Payment System. Accepts the request
 * for processing by default - the interesting outcome (paid / failed) is
 * normally reported later via the webhook, which the orchestrator's unit
 * tests simulate directly.
 *
 * <p>Demo convention, matching {@link MockClientRegistryClient}'s
 * {@code INACTIVE-} prefix: a {@code payeeClientId} prefixed {@code
 * DECLINE-} is refused outright, so the "Payment System declined the
 * request" rejection path (see {@code ClaimOrchestratorService#approveClaim})
 * is exercisable end-to-end through the actual HTTP API, not just in tests
 * that mock this client.
 */
@Component
public class MockPaymentClient implements PaymentClient {

    private static final String DECLINE_PREFIX = "DECLINE-";

    @Override
    public PaymentRequestAcknowledgement requestPayment(UUID claimId, BigDecimal amount, String payeeClientId) {
        if (payeeClientId.startsWith(DECLINE_PREFIX)) {
            return new PaymentRequestAcknowledgement(
                    "PROVIDER-REQ-" + claimId,
                    false,
                    "Insufficient provider balance"
            );
        }
        return new PaymentRequestAcknowledgement(
                "PROVIDER-REQ-" + claimId,
                true,
                "Accepted for processing"
        );
    }
}
