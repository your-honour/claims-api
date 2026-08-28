package com.insurer.claims.entity;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The claim lifecycle, matching the Claim Sequence diagram in the submission.
 *
 * <p>Terminal states are {@link #PAID}, {@link #REJECTED} and
 * {@link #PAYMENT_FAILED} - nothing transitions out of them.
 */
public enum ClaimStatus {

    SUBMITTED,
    CLIENT_VALIDATED,
    POLICY_VALIDATED,
    PENDING_ANALYST_APPROVAL,
    PAYMENT_REQUESTED,
    PAID,
    REJECTED,
    PAYMENT_FAILED;

    /**
     * Legal next states for each status. Encoded once, here, so the
     * "what can happen next" question has a single source of truth rather
     * than being scattered across service methods.
     */
    private static final Map<ClaimStatus, Set<ClaimStatus>> ALLOWED_TRANSITIONS = Map.of(
            SUBMITTED, EnumSet.of(CLIENT_VALIDATED, REJECTED),
            CLIENT_VALIDATED, EnumSet.of(POLICY_VALIDATED, REJECTED),
            POLICY_VALIDATED, EnumSet.of(PENDING_ANALYST_APPROVAL, REJECTED),
            PENDING_ANALYST_APPROVAL, EnumSet.of(PAYMENT_REQUESTED, REJECTED),
            PAYMENT_REQUESTED, EnumSet.of(PAID, PAYMENT_FAILED),
            PAID, EnumSet.noneOf(ClaimStatus.class),
            REJECTED, EnumSet.noneOf(ClaimStatus.class),
            PAYMENT_FAILED, EnumSet.noneOf(ClaimStatus.class)
    );

    public boolean canTransitionTo(ClaimStatus target) {
        return ALLOWED_TRANSITIONS.get(this).contains(target);
    }

    public boolean isTerminal() {
        return ALLOWED_TRANSITIONS.get(this).isEmpty();
    }
}
