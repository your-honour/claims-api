package com.insurer.claims.controller;

import com.insurer.claims.dto.PaymentCallbackRequest;
import com.insurer.claims.exception.ApiError;
import com.insurer.claims.exception.ClaimNotFoundException;
import com.insurer.claims.exception.InvalidClaimStateException;
import com.insurer.claims.exception.InvalidWebhookSignatureException;
import com.insurer.claims.service.ClaimOrchestratorService;
import com.insurer.claims.service.PaymentWebhookSignatureVerifier;
import jakarta.persistence.OptimisticLockException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The Payment System's callback (Fig. 02 sequence diagram, Fig. 03 component
 * diagram: {@code PaymentWebhookController}). This is the async half of the
 * flow - {@code ClaimsController#approveClaim} only requests payment; this
 * is where the outcome actually lands.
 *
 * <p>Two defensive checks before anything is trusted: the signature (this
 * really came from the Payment System) and idempotency (a redelivered event
 * doesn't apply the same outcome twice) - see
 * {@link PaymentWebhookSignatureVerifier} and
 * {@link ClaimOrchestratorService#applyPaymentOutcome}.
 *
 * <p>Exceptions are caught here, not in a central
 * {@code @RestControllerAdvice} - see {@code GlobalExceptionHandler}'s
 * Javadoc for why bean validation is the one exception (literally) to that.
 */
@RestController
@RequestMapping("/claims")
@RequiredArgsConstructor
public class PaymentWebhookController {

    private final ClaimOrchestratorService orchestratorService;
    private final PaymentWebhookSignatureVerifier signatureVerifier;

    @PostMapping("/{claimId}/payment-callback")
    public ResponseEntity<? > handlePaymentCallback(
            @PathVariable UUID claimId,
            @RequestHeader("X-Payment-Signature") String signature,
            @Valid @RequestBody PaymentCallbackRequest callback) {

        if (!claimId.equals(callback.claimId())) {
            return ResponseEntity.badRequest()
                    .body(ApiError.of("claimId in the URL does not match claimId in the payload"));
        }

        try {
            signatureVerifier.verify(callback, signature);
            orchestratorService.applyPaymentOutcome(
                    claimId, callback.eventId(), callback.outcome(), callback.providerPaymentReference());
            return ResponseEntity.ok().build();
        } catch (InvalidWebhookSignatureException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiError.of(e.getMessage()));
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
