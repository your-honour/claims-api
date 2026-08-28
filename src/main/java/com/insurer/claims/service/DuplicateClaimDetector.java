package com.insurer.claims.service;

import com.insurer.claims.entity.Claim;
import com.insurer.claims.repository.ClaimRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * A separate, best-effort duplicate-claim check - distinct from the
 * submission's {@code Idempotency-Key} request header, which only catches
 * an accidental resend of the exact same request.
 *
 * <p>This evaluates a newly submitted claim against other claims on
 * relevant business attributes (policy, claim type, incident date) and
 * flags a possible match for the analyst to review - it never rejects a
 * claim on its own. Two genuinely separate claims can share all three
 * attributes (e.g. two unrelated medical claims on the same policy), so
 * auto-rejecting on this signal would be wrong; a human is better placed to
 * tell a true duplicate from a coincidence.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DuplicateClaimDetector {

    private final ClaimRepository claimRepository;

    /** Empty if no candidate was found. */
    public Optional<UUID> findPossibleDuplicate(Claim claim) {
        try {
            return claimRepository
                    .findPossibleDuplicates(claim.getPolicyNumber(), claim.getClaimType(), claim.getIncidentDate(), claim.getId())
                    .stream()
                    .map(Claim::getId)
                    .findFirst();
        } catch (RuntimeException e) {
            log.error("Failed to check claim {} for possible duplicates", claim.getId(), e);
            throw e;
        }
    }
}
