package com.insurer.claims.client.impl;

import com.insurer.claims.client.PolicyManagerClient;
import com.insurer.claims.client.dto.PolicyValidationRequest;
import com.insurer.claims.client.dto.PolicyValidationResult;
import com.insurer.claims.entity.ClaimType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Set;

/**
 * Stands in for the real Policy Manager System.
 *
 * <p>Demo convention: a policy number is expected in the exact form
 * {@code POL-<clientId>} (optionally prefixed {@code EXPIRED-} to simulate a
 * lapsed policy), and the policyholder is taken to be {@code <clientId>}.
 * This lets a submission's {@code clientId} and {@code policyNumber} agree
 * or deliberately mismatch in test data, without this mock needing a real
 * datastore behind it. Real policy numbers obviously won't follow this
 * shape - it exists purely so this mock is self-contained.
 *
 * <p>A policy number prefixed {@code EXPIRED-} comes back invalid (policy
 * not active), so that rejection path is just as easy to exercise as the
 * happy path. Every mock policy covers all {@link ClaimType}s and carries
 * the same benefit limit.
 */
@Component
public class MockPolicyManagerClient implements PolicyManagerClient {

    private static final String EXPIRED_PREFIX = "EXPIRED-";
    private static final String POLICY_PREFIX = "POL-";
    private static final BigDecimal DEFAULT_BENEFIT_LIMIT = new BigDecimal("500000.00");
    private static final Set<ClaimType> ALL_CLAIM_TYPES_COVERED = EnumSet.allOf(ClaimType.class);

    @Override
    public PolicyValidationResult validatePolicy(PolicyValidationRequest request) {
        String policyNumber = request.policyNumber();

        if (policyNumber.startsWith(EXPIRED_PREFIX)) {
            return PolicyValidationResult.invalid("Policy %s is not active".formatted(policyNumber));
        }

        String policyholderClientId = derivePolicyholder(policyNumber);
        if (!policyholderClientId.equals(request.clientId())) {
            return PolicyValidationResult.invalid("Claimant is not the policyholder on record for this policy");
        }
        if (!ALL_CLAIM_TYPES_COVERED.contains(request.claimType())) {
            return PolicyValidationResult.invalid("Policy does not cover claim type %s".formatted(request.claimType()));
        }
        if (request.claimedAmount().compareTo(DEFAULT_BENEFIT_LIMIT) > 0) {
            return PolicyValidationResult.invalid("Claimed amount exceeds the policy benefit limit");
        }
        return PolicyValidationResult.pass();
    }

    private String derivePolicyholder(String policyNumber) {
        String withoutStatusPrefix = policyNumber.startsWith(EXPIRED_PREFIX)
                ? policyNumber.substring(EXPIRED_PREFIX.length())
                : policyNumber;

        return withoutStatusPrefix.startsWith(POLICY_PREFIX)
                ? withoutStatusPrefix.substring(POLICY_PREFIX.length())
                : withoutStatusPrefix; // fall back: treat the whole thing as the owner
    }
}
