package com.insurer.claims.dto;

import com.insurer.claims.entity.Claim;
import com.insurer.claims.entity.ClaimPriority;
import com.insurer.claims.entity.ClaimStatus;
import com.insurer.claims.entity.ClaimType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * What the Claims Analyst dashboard (and the claimant-facing status check,
 * if one existed) actually sees. Kept separate from {@link Claim} so the
 * entity is free to change shape without breaking the API contract.
 */
public record ClaimResponse(
        UUID id,
        String claimReference,
        String clientId,
        String policyNumber,
        ClaimType claimType,
        LocalDate incidentDate,
        ClaimPriority priority,
        ClaimStatus status,
        BigDecimal claimedAmount,
        String rejectionReason,
        String paymentReference,
        UUID possibleDuplicateOfClaimId,
        OffsetDateTime submittedAt,
        OffsetDateTime updatedAt
) {

    public static ClaimResponse from(Claim claim) {
        return new ClaimResponse(
                claim.getId(),
                claim.getClaimReference(),
                claim.getClientId(),
                claim.getPolicyNumber(),
                claim.getClaimType(),
                claim.getIncidentDate(),
                claim.getPriority(),
                claim.getStatus(),
                claim.getClaimedAmount(),
                claim.getRejectionReason(),
                claim.getPaymentReference(),
                claim.getPossibleDuplicateOfClaimId(),
                claim.getSubmittedAt(),
                claim.getUpdatedAt()
        );
    }
}
