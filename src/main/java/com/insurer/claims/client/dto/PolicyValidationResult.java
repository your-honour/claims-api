package com.insurer.claims.client.dto;

/**
 * The Policy Manager System's verdict on a {@link PolicyValidationRequest} -
 * a pass/fail decision, not a data record. The Policy Manager owns the
 * comparison against the policy, benefits and portfolio it holds; the
 * Claims System's orchestrator just acts on the verdict.
 */
public record PolicyValidationResult(boolean valid, String reason) {

    public static PolicyValidationResult pass() {
        return new PolicyValidationResult(true, null);
    }

    public static PolicyValidationResult invalid(String reason) {
        return new PolicyValidationResult(false, reason);
    }
}
