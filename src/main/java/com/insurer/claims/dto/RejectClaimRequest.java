package com.insurer.claims.dto;

import jakarta.validation.constraints.NotBlank;

/** Body of {@code POST /claims/{id}/reject} - an analyst can reject a claim outright (e.g. suspected fraud). */
public record RejectClaimRequest(

        @NotBlank(message = "reason is required")
        String reason
) {
}
