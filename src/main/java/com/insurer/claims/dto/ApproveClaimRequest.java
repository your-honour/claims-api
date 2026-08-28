package com.insurer.claims.dto;

import jakarta.validation.constraints.NotBlank;

/** Body of {@code POST /claims/{id}/approve} - who on the Claims Analyst dashboard approved it. */
public record ApproveClaimRequest(

        @NotBlank(message = "analystId is required")
        String analystId
) {
}
