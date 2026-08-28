package com.insurer.claims.service;

import com.insurer.claims.client.dto.ClientValidationRequest;
import com.insurer.claims.client.dto.ClientValidationResult;
import com.insurer.claims.client.ClientRegistryClient;
import com.insurer.claims.client.PaymentClient;
import com.insurer.claims.client.dto.PaymentRequestAcknowledgement;
import com.insurer.claims.client.dto.PolicyValidationRequest;
import com.insurer.claims.client.dto.PolicyValidationResult;
import com.insurer.claims.client.PolicyManagerClient;
import com.insurer.claims.entity.Claim;
import com.insurer.claims.entity.ClaimStatus;
import com.insurer.claims.dto.ClaimResponse;
import com.insurer.claims.dto.ClaimSubmissionRequest;
import com.insurer.claims.dto.PaymentOutcome;
import com.insurer.claims.exception.ClaimNotFoundException;
import com.insurer.claims.exception.InvalidClaimStateException;
import com.insurer.claims.repository.ClaimRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;
import java.util.List;

/**
 * The "orchestration" logic referred to throughout the submission: this is
 * what turns five previously disconnected systems into one workflow.
 *
 * <p>Client and policy validation are deliberately not this class's job -
 * {@link ClientRegistryClient#validateClient} and
 * {@link PolicyManagerClient#validatePolicy} return a verdict, not a data
 * record, because the brief states those systems handle that validation.
 * This class only sequences the workflow and reacts to each verdict.
 *
 * <p>Two distinct mechanisms guard against duplicate claims, deliberately
 * kept separate: an idempotency key (the {@code Idempotency-Key} request
 * header, not a body field - see {@code ClaimsController#submitClaim})
 * resolves an accidental resend of the same request to the same claim, and
 * {@link DuplicateClaimDetector} flags - but never auto-rejects - a claim
 * that looks like it might be a genuine second submission of the same
 * incident. There is no database uniqueness constraint on policy/client:
 * a policy can have multiple legitimate claims, so that call is left to the
 * analyst.
 *
 * <p>Every submitted claim also gets a short, human-readable reference
 * (see {@link ClaimReferenceGenerator}) - the UUID id stays the real
 * identifier used everywhere else, but an analyst can't easily write one
 * down or read it out over the phone.
 *
 * <p>Everything here is synchronous except the payment outcome, which
 * necessarily arrives later via {@link #applyPaymentOutcome}, called from
 * the webhook controller once the Payment System reports back.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ClaimOrchestratorService {

    private final ClaimRepository claimRepository;
    private final ClientRegistryClient clientRegistryClient;
    private final PolicyManagerClient policyManagerClient;
    private final PaymentClient paymentClient;
    private final DuplicateClaimDetector duplicateClaimDetector;
    private final ClaimReferenceGenerator claimReferenceGenerator;
    private final ProcessedPaymentEventRecorder processedPaymentEventRecorder;

    @Transactional
    public ClaimResponse submitClaim(ClaimSubmissionRequest request, String idempotencyKey) {
        try {
            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                Optional<Claim> existing = claimRepository.findByIdempotencyKey(idempotencyKey);
                if (existing.isPresent()) {
                    log.info("Idempotency key {} already used for claim {} - returning existing claim, not resubmitting",
                            idempotencyKey, existing.get().getId());
                    return ClaimResponse.from(existing.get());
                }
            }

            Claim claim = Claim.submit(
                    claimReferenceGenerator.next(),
                    request.clientId(),
                    request.claimantFullName(),
                    request.claimantIdNumber(),
                    request.policyNumber(),
                    request.claimType(),
                    request.incidentDate(),
                    request.claimedAmount(),
                    idempotencyKey
            );
            claim = claimRepository.save(claim);
            log.info("Claim {} ({}) submitted for client {} (priority={})",
                    claim.getClaimReference(), claim.getId(), claim.getClientId(), claim.getPriority());

            ClientValidationResult clientResult = clientRegistryClient.validateClient(
                    new ClientValidationRequest(claim.getClientId(), claim.getClaimantFullName(), claim.getClaimantIdNumber()));
            if (!clientResult.valid()) {
                claim.reject(clientResult.reason());
                return ClaimResponse.from(claimRepository.save(claim));
            }
            claim.transitionTo(ClaimStatus.CLIENT_VALIDATED);

            PolicyValidationResult policyResult = policyManagerClient.validatePolicy(
                    new PolicyValidationRequest(claim.getPolicyNumber(), claim.getClientId(), claim.getClaimType(), claim.getClaimedAmount()));
            if (!policyResult.valid()) {
                claim.reject(policyResult.reason());
                return ClaimResponse.from(claimRepository.save(claim));
            }
            claim.transitionTo(ClaimStatus.POLICY_VALIDATED);

            duplicateClaimDetector.findPossibleDuplicate(claim).ifPresent(claim::flagPossibleDuplicate);

            claim.transitionTo(ClaimStatus.PENDING_ANALYST_APPROVAL);

            return ClaimResponse.from(claimRepository.save(claim));
        } catch (RuntimeException e) {
            log.error("Failed to submit claim for client {}", request.clientId(), e);
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public ClaimResponse getClaim(UUID claimId) {
        try {
            return ClaimResponse.from(getOrThrow(claimId));
        } catch (RuntimeException e) {
            log.error("Failed to get claim {}", claimId, e);
            throw e;
        }
    }

    /** The analyst's triage queue - HIGH priority (death claims) first, then oldest-submitted-first. */
    @Transactional(readOnly = true)
    public List<ClaimResponse> getAnalystQueue() {
        try {
            return claimRepository.findQueueByStatus(ClaimStatus.PENDING_ANALYST_APPROVAL)
                    .stream()
                    .map(ClaimResponse::from)
                    .toList();
        } catch (RuntimeException e) {
            log.error("Failed to load the analyst queue", e);
            throw e;
        }
    }

    /**
     * Every claim regardless of status, newest-submitted-first. Not part of
     * the analyst's actual workflow (that's {@link #getAnalystQueue()}) -
     * this exists for browsing/testing, so a claim that's already moved past
     * PENDING_ANALYST_APPROVAL (approved, rejected, paid...) is still easy
     * to find.
     */
    @Transactional(readOnly = true)
    public List<ClaimResponse> getAllClaims() {
        try {
            return claimRepository.findAllByOrderBySubmittedAtDesc()
                    .stream()
                    .map(ClaimResponse::from)
                    .toList();
        } catch (RuntimeException e) {
            log.error("Failed to load all claims", e);
            throw e;
        }
    }

    /**
     * The analyst approves a claim that passed validation. Payment is only
     * actually requested - and the claim only moves to PAYMENT_REQUESTED -
     * once the Payment System has acknowledged the request.
     */
    @Transactional
    public ClaimResponse approveClaim(UUID claimId, String analystId) {
        try {
            Claim claim = getOrThrow(claimId);
            if (claim.getStatus() != ClaimStatus.PENDING_ANALYST_APPROVAL) {
                throw new InvalidClaimStateException(
                        "Claim %s is not awaiting analyst approval (current status: %s)"
                                .formatted(claimId, claim.getStatus()));
            }

            claim.recordAnalystApproval(analystId);
            PaymentRequestAcknowledgement ack =
                    paymentClient.requestPayment(claim.getId(), claim.getClaimedAmount(), claim.getClientId());

            if (ack.accepted()) {
                claim.transitionTo(ClaimStatus.PAYMENT_REQUESTED);
                log.info("Claim {} approved by {}, payment requested ({})", claimId, analystId, ack.providerRequestId());
            } else {
                claim.reject("Payment System declined the request: " + ack.message());
                log.warn("Claim {} payment request declined by Payment System: {}", claimId, ack.message());
            }

            return ClaimResponse.from(claimRepository.save(claim));
        } catch (RuntimeException e) {
            log.error("Failed to approve claim {}", claimId, e);
            throw e;
        }
    }

    /** The analyst can reject a claim outright - including one that passed validation (e.g. suspected fraud). */
    @Transactional
    public ClaimResponse rejectClaim(UUID claimId, String reason) {
        try {
            Claim claim = getOrThrow(claimId);
            claim.reject(reason);
            return ClaimResponse.from(claimRepository.save(claim));
        } catch (RuntimeException e) {
            log.error("Failed to reject claim {}", claimId, e);
            throw e;
        }
    }

    /**
     * Applies the outcome reported by the Payment System's webhook.
     * Idempotent by {@code eventId} - a redelivered webhook is a no-op.
     *
     * <p>Backed by {@link com.insurer.claims.entity.ProcessedPaymentEvent}'s
     * unique {@code eventId} column via {@link ProcessedPaymentEventRecorder},
     * not an in-memory set - see that class's Javadoc for the known
     * limitation (a truly concurrent redelivery isn't guaranteed a clean
     * no-op) accepted to keep this simple.
     */
    @Transactional
    public void applyPaymentOutcome(UUID claimId, String eventId, PaymentOutcome outcome, String providerPaymentReference) {
        try {
            if (!processedPaymentEventRecorder.recordIfNew(eventId)) {
                log.info("Payment webhook event {} already processed for claim {} - ignoring redelivery", eventId, claimId);
                return;
            }

            Claim claim = getOrThrow(claimId);
            claim.recordPaymentOutcome(outcome == PaymentOutcome.SUCCESSFUL, providerPaymentReference);
            claimRepository.save(claim);
            log.info("Claim {} payment outcome applied: {}", claimId, outcome);
        } catch (RuntimeException e) {
            log.error("Failed to apply payment outcome for claim {} (event {})", claimId, eventId, e);
            throw e;
        }
    }

    private Claim getOrThrow(UUID claimId) {
        return claimRepository.findById(claimId).orElseThrow(() -> new ClaimNotFoundException(claimId));
    }
}
