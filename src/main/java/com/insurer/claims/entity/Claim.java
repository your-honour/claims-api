package com.insurer.claims.entity;

import com.insurer.claims.exception.InvalidClaimStateException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

/**
 * A claim moving through the workflow described in the case study.
 *
 * <p>Deliberately a "rich" entity rather than an anaemic bag of getters/
 * setters: state changes go through {@link #transitionTo(ClaimStatus)} so
 * illegal transitions (e.g. requesting payment on a claim that was never
 * approved) fail fast, in one place, instead of being trusted to whichever
 * service method happens to call them.
 */
@Entity
@Table(name = "claims")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA only
public class Claim {

    /** South Africa has no DST, so this offset is fixed year-round. */
    private static final ZoneId SAST = ZoneId.of("Africa/Johannesburg");

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * Short, human-readable reference (e.g. {@code CLM-000123}) for the
     * analyst to write down or read out loud - the UUID {@link #id} above
     * stays the real identifier everywhere else (URLs, foreign keys). See
     * {@code ClaimReferenceGenerator}.
     */
    @Column(nullable = false, unique = true)
    private String claimReference;

    @Column(nullable = false)
    private String clientId;

    /** As submitted on the claim - compared against the Client Registry record during validation. */
    @Column(nullable = false)
    private String claimantFullName;

    @Column(nullable = false)
    private String claimantIdNumber;

    @Column(nullable = false)
    private String policyNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ClaimType claimType;

    /** When the claim event itself happened (date of death, injury, treatment...) - not when it was submitted. */
    @Column(nullable = false)
    private LocalDate incidentDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ClaimPriority priority;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ClaimStatus status;

    @Column(nullable = false)
    private BigDecimal claimedAmount;

    private String rejectionReason;

    private String paymentReference;

    private String approvedByAnalystId;

    /**
     * Channel-supplied idempotency key, so a network retry or a double-click
     * on the controller form's submit button doesn't create a second claim.
     * Optional and unique-when-present (a real Channel System should always
     * send one; kept optional here so existing demo/test flows without one
     * still work - see README).
     */
    @Column(unique = true)
    private String idempotencyKey;

    /**
     * Set by {@code DuplicateClaimDetector} when another non-rejected claim
     * matches on policy/claim type/incident date. A signal for the analyst,
     * not a block - two legitimate claims can share all three (e.g. two
     * separate medical claims against the same policy), so this is never
     * used to auto-reject.
     */
    private UUID possibleDuplicateOfClaimId;

    @Column(nullable = false, updatable = false)
    private OffsetDateTime submittedAt;

    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    /**
     * Optimistic lock. The analyst queue is shared (deliberately - see README),
     * so two analysts can pull the same claim and both call approve/reject at
     * once; without this, both could pass the {@code status} check and the
     * second write would silently clobber the first, potentially double-firing
     * the payment request. A losing concurrent write now fails with
     * {@link jakarta.persistence.OptimisticLockException}, caught and mapped
     * to a 409 in {@code ClaimsController}/{@code PaymentWebhookController}.
     */
    @Version
    private long version;

    public static Claim submit(String claimReference, String clientId, String claimantFullName, String claimantIdNumber,
                                String policyNumber, ClaimType claimType, LocalDate incidentDate,
                                BigDecimal claimedAmount, String idempotencyKey) {
        Claim claim = new Claim();
        claim.claimReference = claimReference;
        claim.clientId = clientId;
        claim.claimantFullName = claimantFullName;
        claim.claimantIdNumber = claimantIdNumber;
        claim.policyNumber = policyNumber;
        claim.claimType = claimType;
        claim.incidentDate = incidentDate;
        claim.priority = claimType.defaultPriority();
        claim.claimedAmount = claimedAmount;
        claim.idempotencyKey = idempotencyKey;
        claim.status = ClaimStatus.SUBMITTED;
        OffsetDateTime now = OffsetDateTime.now(SAST);
        claim.submittedAt = now;
        claim.updatedAt = now;
        return claim;
    }

    /** Moves the claim forward. Throws if {@code target} isn't a legal next state from here. */
    public void transitionTo(ClaimStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidClaimStateException(
                    "Claim %s cannot move from %s to %s".formatted(id, status, target));
        }
        this.status = target;
        this.updatedAt = OffsetDateTime.now(SAST);
    }

    public void reject(String reason) {
        transitionTo(ClaimStatus.REJECTED);
        this.rejectionReason = reason;
    }

    /**
     * Records who approved the claim. Deliberately does not itself change
     * {@link #status} - the orchestrator only moves the claim to
     * {@link ClaimStatus#PAYMENT_REQUESTED} once the Payment System has
     * actually accepted the request (see {@code ClaimOrchestratorService}),
     * so "approved by" and "payment requested" can't drift out of sync.
     */
    public void recordAnalystApproval(String analystId) {
        this.approvedByAnalystId = analystId;
    }

    public void recordPaymentOutcome(boolean successful, String paymentReference) {
        transitionTo(successful ? ClaimStatus.PAID : ClaimStatus.PAYMENT_FAILED);
        this.paymentReference = paymentReference;
    }

    public boolean isHighPriority() {
        return priority == ClaimPriority.HIGH;
    }

    /** Flags this claim as a possible duplicate of {@code otherClaimId} - a signal for the analyst, not a rejection. */
    public void flagPossibleDuplicate(UUID otherClaimId) {
        this.possibleDuplicateOfClaimId = otherClaimId;
    }
}
