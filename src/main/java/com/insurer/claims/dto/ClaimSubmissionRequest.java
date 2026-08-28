package com.insurer.claims.dto;

import com.insurer.claims.entity.ClaimType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The JSON payload the Channel System (controller form) posts to {@code POST /claims/submit}.
 *
 * <p>The submission's idempotency key travels as the {@code Idempotency-Key}
 * request header, not a body field - it's transport-level plumbing (a
 * network retry or a double-click on the submit button resolves to the same
 * claim instead of creating a second one), not part of the claim data
 * itself. See {@code ClaimsController#submitClaim} and {@code
 * ClaimOrchestratorService#submitClaim}. It is unrelated to {@code
 * possibleDuplicateOfClaimId} on {@link ClaimResponse}, which flags two
 * claims a human should compare, not an accidental resend of the same one.
 */
public record ClaimSubmissionRequest(

        @NotBlank(message = "clientId is required")
        String clientId,

        @NotBlank(message = "claimantFullName is required")
        String claimantFullName,

        @NotBlank(message = "claimantIdNumber is required")
        String claimantIdNumber,

        @NotBlank(message = "policyNumber is required")
        String policyNumber,

        @NotNull(message = "claimType is required")
        ClaimType claimType,

        @NotNull(message = "incidentDate is required")
        @PastOrPresent(message = "incidentDate cannot be in the future")
        LocalDate incidentDate,

        @NotNull(message = "claimedAmount is required")
        @DecimalMin(value = "0.01", message = "claimedAmount must be positive")
        BigDecimal claimedAmount
) {
}
