package com.insurer.claims.repository;

import com.insurer.claims.entity.Claim;
import com.insurer.claims.entity.ClaimStatus;
import com.insurer.claims.entity.ClaimType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reproduces the concurrency hole the shared analyst queue opens up: two
 * analysts can both load the same PENDING_ANALYST_APPROVAL claim before
 * either writes back. Without {@link Claim#getVersion()} being enforced by
 * JPA, the second write would silently overwrite the first, and both
 * approvals would end up triggering a payment request for the same claim.
 */
@DataJpaTest
class ClaimOptimisticLockingTest {

    @Autowired
    private ClaimRepository claimRepository;
    @Autowired
    private TestEntityManager entityManager;

    @Test
    void secondAnalystsSaveFailsWhenClaimWasConcurrentlyModified() {
        Claim claim = Claim.submit("CLM-000001", "CL-1001", "Jane Doe", "8001015800083", "POL-CL-1001",
                ClaimType.DEATH, LocalDate.of(2026, 8, 1), new BigDecimal("100000.00"), null);
        claim.transitionTo(ClaimStatus.CLIENT_VALIDATED);
        claim.transitionTo(ClaimStatus.POLICY_VALIDATED);
        claim.transitionTo(ClaimStatus.PENDING_ANALYST_APPROVAL);
        UUID claimId = claimRepository.saveAndFlush(claim).getId();
        entityManager.detach(claim);

        // Two analysts independently pull the same claim from the shared queue,
        // each into their own persistence context (a real request each gets its
        // own EntityManager) - detach between fetches so Hibernate's identity
        // map doesn't just hand back the same in-memory instance twice.
        Claim analystAView = claimRepository.findById(claimId).orElseThrow();
        entityManager.detach(analystAView);
        Claim analystBView = claimRepository.findById(claimId).orElseThrow();
        entityManager.detach(analystBView);

        // Analyst A approves first and their write lands.
        analystAView.recordAnalystApproval("analyst-A");
        analystAView.transitionTo(ClaimStatus.PAYMENT_REQUESTED);
        claimRepository.saveAndFlush(analystAView);

        // Analyst B's copy is now stale - their write must be rejected, not silently applied.
        analystBView.recordAnalystApproval("analyst-B");
        analystBView.transitionTo(ClaimStatus.PAYMENT_REQUESTED);

        assertThatThrownBy(() -> claimRepository.saveAndFlush(analystBView))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        Claim persisted = claimRepository.findById(claimId).orElseThrow();
        assertThat(persisted.getApprovedByAnalystId()).isEqualTo("analyst-A");
    }
}
