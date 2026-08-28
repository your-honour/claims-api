package com.insurer.claims.repository;

import com.insurer.claims.entity.Claim;
import com.insurer.claims.entity.ClaimStatus;
import com.insurer.claims.entity.ClaimType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.QueryHint;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ClaimRepository extends JpaRepository<Claim, UUID> {

    /**
     * The analyst's triage queue: claims waiting on approval, HIGH priority
     * first (death claims), oldest-submitted-first within a priority tier.
     *
     * <p>Ordering is spelled out explicitly via CASE rather than relying on
     * {@code ClaimPriority}'s enum ordinal or its alphabetical string value -
     * both would happen to sort correctly today, but only by accident, and
     * that's exactly the kind of thing that quietly breaks later.
     */
    @Query("""
            select c from Claim c
            where c.status = :status
            order by case when c.priority = com.insurer.claims.entity.ClaimPriority.HIGH then 0 else 1 end,
                     c.submittedAt asc
            """)
    List<Claim> findQueueByStatus(@Param("status") ClaimStatus status);

    /** Every claim regardless of status, newest-submitted-first - for browsing/testing, not triage. */
    List<Claim> findAllByOrderBySubmittedAtDesc();

    /** Backs the submission idempotency check - see {@code ClaimOrchestratorService#submitClaim}. */
    Optional<Claim> findByIdempotencyKey(String idempotencyKey);

    /**
     * Candidate duplicates for {@code DuplicateClaimDetector}: other claims
     * against the same policy, same claim type, same incident date. REJECTED
     * claims are excluded - a claim that was thrown out isn't a real prior
     * claim to flag against. Deliberately no uniqueness constraint backs
     * this - a policy can have multiple legitimate claims, so this is a
     * best-effort signal for the analyst, not a lookup that must return at
     * most one row.
     *
     * <p>{@code flushMode = COMMIT}: this always runs mid-way through {@code
     * ClaimOrchestratorService#submitClaim}, with the current (soon-to-be
     * PENDING_ANALYST_APPROVAL) claim already dirty in the persistence
     * context. Hibernate's default {@code AUTO} flush mode would flush those
     * pending changes before running any query against the same table, in
     * case the query needs to see them - producing a second, wasted {@code
     * UPDATE} (and, incidentally, an extra {@code version} bump) on top of
     * the one that happens at commit. That protection isn't needed here:
     * {@code c.id <> :excludeClaimId} excludes the current claim from the
     * results, so its own unflushed changes can never affect this query's
     * answer. Skipping the auto-flush is safe, not just faster.
     */
    @QueryHints(@QueryHint(name = "org.hibernate.flushMode", value = "COMMIT"))
    @Query("""
            select c from Claim c
            where c.policyNumber = :policyNumber
              and c.claimType = :claimType
              and c.incidentDate = :incidentDate
              and c.id <> :excludeClaimId
              and c.status <> com.insurer.claims.entity.ClaimStatus.REJECTED
            """)
    List<Claim> findPossibleDuplicates(@Param("policyNumber") String policyNumber,
                                        @Param("claimType") ClaimType claimType,
                                        @Param("incidentDate") LocalDate incidentDate,
                                        @Param("excludeClaimId") UUID excludeClaimId);
}
