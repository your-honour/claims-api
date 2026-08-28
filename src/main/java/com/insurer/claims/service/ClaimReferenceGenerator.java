package com.insurer.claims.service;

import com.insurer.claims.entity.ClaimSequence;
import com.insurer.claims.repository.ClaimSequenceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Generates the human-readable claim reference shown to the analyst
 * (e.g. {@code CLM-000123}) - something they can actually write down or
 * read out over the phone, unlike the claim's UUID primary key. The UUID
 * stays the real identifier everywhere else (URLs, foreign keys); this is
 * purely a display value, generated once at submission time.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ClaimReferenceGenerator {

    /** Zero-padded to 6 digits (CLM-000123); grows naturally past that, never truncates. */
    private static final String FORMAT = "CLM-%06d";

    private final ClaimSequenceRepository claimSequenceRepository;

    public String next() {
        try {
            long nextId = claimSequenceRepository.save(new ClaimSequence()).getId();
            return FORMAT.formatted(nextId);
        } catch (RuntimeException e) {
            log.error("Failed to generate a claim reference", e);
            throw e;
        }
    }
}
