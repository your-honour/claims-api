package com.insurer.claims.service;

import com.insurer.claims.entity.ProcessedPaymentEvent;
import com.insurer.claims.repository.ProcessedPaymentEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * Records a payment webhook event as processed - backs the idempotency
 * check in {@code ClaimOrchestratorService#applyPaymentOutcome}. DB-backed
 * via {@link ProcessedPaymentEvent}'s unique {@code eventId} column, not an
 * in-memory set: that survives a restart and works across every ECS
 * Fargate replica behind the ALB, which a plain in-memory {@code Set}
 * can't.
 *
 * <p>Known limitation: the check-then-insert here isn't atomic. Two
 * deliveries of the same event arriving genuinely concurrently (as opposed
 * to a plain sequential redelivery, the common case) could both pass the
 * {@code existsByEventId} check before either commits; the loser then fails
 * on the unique constraint. An earlier version of this class caught that
 * failure in the same transaction as the caller's - which turned out not to
 * work, because Spring marks a transaction rollback-only the instant an
 * exception is thrown inside it, regardless of whether the exception is
 * caught afterwards, so the caller's later commit failed anyway with {@code
 * UnexpectedRollbackException}. Fixing that properly means recording the
 * event in its own transaction (a separate {@code REQUIRES_NEW}-annotated
 * bean, since Spring's proxy-based {@code @Transactional} doesn't apply to
 * self-invocation) - deliberately not done here, to keep this class simple
 * to read. The accepted trade-off: a truly concurrent redelivery can
 * surface as a 500 instead of a clean no-op, rather than being silently
 * absorbed.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProcessedPaymentEventRecorder {

    private final ProcessedPaymentEventRepository processedPaymentEventRepository;

    /**
     * @return true if this call recorded {@code eventId} (safe to proceed and
     * apply the outcome); false if it was already recorded - a plain
     * sequential redelivery, the case this is actually meant to handle.
     */
    public boolean recordIfNew(String eventId) {
        try {
            if (processedPaymentEventRepository.existsByEventId(eventId)) {
                return false;
            }
            processedPaymentEventRepository.save(ProcessedPaymentEvent.record(eventId));
            return true;
        } catch (DataIntegrityViolationException e) {
            return false;
        } catch (RuntimeException e) {
            log.error("Failed to record payment webhook event {} as processed", eventId, e);
            throw e;
        }
    }
}
