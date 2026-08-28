package com.insurer.claims.entity;

/**
 * Triage priority for the analyst queue. {@link #HIGH} claims (currently:
 * death claims - see {@link ClaimType}) are sorted ahead of everything else;
 * within a priority tier, claims are ordered oldest-submitted-first.
 */
public enum ClaimPriority {
    HIGH,
    STANDARD
}
