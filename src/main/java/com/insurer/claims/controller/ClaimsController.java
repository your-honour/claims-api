package com.insurer.claims.controller;

import com.insurer.claims.dto.ApproveClaimRequest;
import com.insurer.claims.dto.ClaimResponse;
import com.insurer.claims.dto.ClaimSubmissionRequest;
import com.insurer.claims.dto.RejectClaimRequest;
import com.insurer.claims.exception.ApiError;
import com.insurer.claims.exception.ClaimNotFoundException;
import com.insurer.claims.exception.InvalidClaimStateException;
import com.insurer.claims.service.ClaimOrchestratorService;
import jakarta.persistence.OptimisticLockException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * The endpoints described in the submission's Container/Component diagrams:
 * claim intake from the Channel System (controller form), and the Claims Analyst's
 * queue and approve/reject actions.
 *
 * <p>Payment status updates arrive on a separate controller -
 * {@link com.insurer.claims.controller.PaymentWebhookController} - since that's a
 * different caller (the Payment System, not the controller form or the analyst)
 * with a different trust model (signature-verified, not session/analyst
 * authenticated).
 *
 * <p>Exceptions are caught here, per-method, rather than in a central
 * {@code @RestControllerAdvice} - see {@code GlobalExceptionHandler}'s
 * Javadoc for the one framework-forced exception (bean validation) that
 * can't follow that pattern.
 */
@RestController
@RequestMapping("/claims")
@RequiredArgsConstructor
public class ClaimsController {

    private final ClaimOrchestratorService orchestratorService;

    /**
     * Intake: the Channel System (controller form) posts the claim JSON here.
     * The optional {@code Idempotency-Key} header - not a body field - lets a
     * retried/double-clicked submission resolve to the same claim instead of
     * creating a second one; see {@code ClaimOrchestratorService#submitClaim}.
     *
     * <p>No try/catch needed: a brand-new claim can't already be missing,
     * already be in an illegal state, or lose an optimistic-lock race
     * against itself.
     */
    @PostMapping("/submit")
    public ResponseEntity<ClaimResponse> submitClaim(
            @Valid @RequestBody ClaimSubmissionRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        ClaimResponse response = orchestratorService.submitClaim(request, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{claimId}")
    public ResponseEntity<?> getClaim(@PathVariable UUID claimId) {
        try {
            return ResponseEntity.ok(orchestratorService.getClaim(claimId));
        } catch (ClaimNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of(e.getMessage()));
        }
    }

    /** The Claims Analyst dashboard's triage queue - HIGH priority (death claims) first. */
    @GetMapping("/queue")
    public List<ClaimResponse> getAnalystQueue() {
        return orchestratorService.getAnalystQueue();
    }

    /** Every claim regardless of status - for browsing/testing, not the analyst's actual workflow. */
    @GetMapping
    public List<ClaimResponse> getAllClaims() {
        return orchestratorService.getAllClaims();
    }

    @PostMapping("/{claimId}/approve")
    public ResponseEntity<?> approveClaim(@PathVariable UUID claimId, @Valid @RequestBody ApproveClaimRequest request) {
        try {
            return ResponseEntity.ok(orchestratorService.approveClaim(claimId, request.analystId()));
        } catch (ClaimNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of(e.getMessage()));
        } catch (InvalidClaimStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.of(e.getMessage()));
        } catch (ObjectOptimisticLockingFailureException | OptimisticLockException e) {
            // Two analysts approved the same queued claim at once - see Claim#version.
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiError.of("Claim was modified concurrently - please retry"));
        }
    }

    @PostMapping("/{claimId}/reject")
    public ResponseEntity<?> rejectClaim(@PathVariable UUID claimId, @Valid @RequestBody RejectClaimRequest request) {
        try {
            return ResponseEntity.ok(orchestratorService.rejectClaim(claimId, request.reason()));
        } catch (ClaimNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of(e.getMessage()));
        } catch (InvalidClaimStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.of(e.getMessage()));
        } catch (ObjectOptimisticLockingFailureException | OptimisticLockException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiError.of("Claim was modified concurrently - please retry"));
        }
    }
}
