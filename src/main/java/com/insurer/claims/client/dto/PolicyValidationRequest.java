package com.insurer.claims.client.dto;

import com.insurer.claims.entity.ClaimType;

import java.math.BigDecimal;

/**
 * What the Claims System sends the Policy Manager System to validate a
 * claim against the policy, benefits and portfolio it owns - active status,
 * policyholder match, claim type coverage, and benefit limit are all the
 * Policy Manager's own checks (see {@link PolicyValidationResult}).
 */
public record PolicyValidationRequest(
        String policyNumber,
        String clientId,
        ClaimType claimType,
        BigDecimal claimedAmount
) {
}
