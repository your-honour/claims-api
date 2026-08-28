package com.insurer.claims.client.dto;

/**
 * What the Claims System sends the Client Registry System to validate a
 * claim's claimant - the registry owns the comparison against its own
 * record (see {@link ClientValidationResult}), the Claims System does not.
 */
public record ClientValidationRequest(
        String clientId,
        String claimantFullName,
        String claimantIdNumber
) {
}
