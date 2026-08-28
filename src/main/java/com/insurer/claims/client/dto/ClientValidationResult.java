package com.insurer.claims.client.dto;

/**
 * The Client Registry System's verdict on a {@link ClientValidationRequest} -
 * a pass/fail decision, not a data record. This is the Client Registry
 * "handling client validation" in the literal sense the brief describes:
 * it owns the comparison between the submitted claimant details and its own
 * record, and the Claims System's orchestrator just acts on the verdict.
 */
public record ClientValidationResult(boolean valid, String reason) {

    public static ClientValidationResult pass() {
        return new ClientValidationResult(true, null);
    }

    public static ClientValidationResult invalid(String reason) {
        return new ClientValidationResult(false, reason);
    }
}
