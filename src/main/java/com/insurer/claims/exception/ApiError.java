package com.insurer.claims.exception;

import java.time.OffsetDateTime;
import java.time.ZoneId;

/** Uniform error body - built by {@link GlobalExceptionHandler} for validation failures, and directly by the controllers' own try/catch blocks for everything else. */
public record ApiError(String message, OffsetDateTime timestamp) {

    /** South Africa has no DST, so this offset is fixed year-round. */
    private static final ZoneId SAST = ZoneId.of("Africa/Johannesburg");

    public static ApiError of(String message) {
        return new ApiError(message, OffsetDateTime.now(SAST));
    }
}
