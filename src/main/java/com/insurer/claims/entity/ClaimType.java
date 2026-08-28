package com.insurer.claims.entity;

/**
 * The kind of claim being submitted. Drives {@link ClaimPriority} - this is
 * how a death claim ends up triaged ahead of everything else in the
 * analyst's queue, per the case study's "some claims... require fast and
 * efficient processing" requirement.
 */
public enum ClaimType {

    DEATH(ClaimPriority.HIGH),
    DISABILITY(ClaimPriority.STANDARD),
    CRITICAL_ILLNESS(ClaimPriority.STANDARD),
    MEDICAL(ClaimPriority.STANDARD),
    OTHER(ClaimPriority.STANDARD);

    private final ClaimPriority defaultPriority;

    ClaimType(ClaimPriority defaultPriority) {
        this.defaultPriority = defaultPriority;
    }

    public ClaimPriority defaultPriority() {
        return defaultPriority;
    }
}
