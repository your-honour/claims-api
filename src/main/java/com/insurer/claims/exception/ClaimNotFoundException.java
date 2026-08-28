package com.insurer.claims.exception;

import java.util.UUID;

public class ClaimNotFoundException extends RuntimeException {
    public ClaimNotFoundException(UUID claimId) {
        super("Claim not found: " + claimId);
    }
}
