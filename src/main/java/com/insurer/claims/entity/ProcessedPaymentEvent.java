package com.insurer.claims.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

/**
 * A marker row for a payment webhook event that has already been applied -
 * backs the idempotency check in {@code ClaimOrchestratorService#applyPaymentOutcome}.
 *
 * <p>DB-backed rather than an in-memory set on purpose: an in-memory
 * collection doesn't survive a restart, doesn't work across the multiple
 * ECS Fargate replicas behind the ALB, and - the sharper problem - isn't
 * actually safe under concurrent redelivery (a plain {@code HashSet.add()}
 * has no synchronization, so two overlapping deliveries of the same event
 * could both pass the check). The {@code eventId} unique constraint here
 * gives a real, DB-enforced guarantee instead.
 */
@Entity
@Table(name = "processed_payment_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProcessedPaymentEvent {

    /** South Africa has no DST, so this offset is fixed year-round. */
    private static final ZoneId SAST = ZoneId.of("Africa/Johannesburg");

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String eventId;

    @Column(nullable = false)
    private OffsetDateTime processedAt;

    public static ProcessedPaymentEvent record(String eventId) {
        ProcessedPaymentEvent event = new ProcessedPaymentEvent();
        event.eventId = eventId;
        event.processedAt = OffsetDateTime.now(SAST);
        return event;
    }
}
