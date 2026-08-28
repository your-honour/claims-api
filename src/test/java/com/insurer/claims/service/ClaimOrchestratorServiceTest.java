package com.insurer.claims.service;

import com.insurer.claims.client.ClientRegistryClient;
import com.insurer.claims.client.PaymentClient;
import com.insurer.claims.client.dto.ClientValidationResult;
import com.insurer.claims.client.dto.PaymentRequestAcknowledgement;
import com.insurer.claims.client.dto.PolicyValidationResult;
import com.insurer.claims.client.PolicyManagerClient;
import com.insurer.claims.entity.Claim;
import com.insurer.claims.entity.ClaimStatus;
import com.insurer.claims.entity.ClaimType;
import com.insurer.claims.dto.ClaimResponse;
import com.insurer.claims.dto.ClaimSubmissionRequest;
import com.insurer.claims.dto.PaymentOutcome;
import com.insurer.claims.exception.ClaimNotFoundException;
import com.insurer.claims.exception.InvalidClaimStateException;
import com.insurer.claims.repository.ClaimRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the orchestrator - the piece that turns the four bullet
 * points in the brief ("client validation... policy validation... payment
 * request... payment status update") into actual behaviour. All three
 * external systems are mocked here directly (not via the sample
 * MockXClient beans), so each test controls exactly what "the outside
 * world" says.
 */
@ExtendWith(MockitoExtension.class)
class ClaimOrchestratorServiceTest {

    private static final String CLIENT_ID = "CL-1001";
    private static final String CLAIMANT_NAME = "Jane Doe";
    private static final String CLAIMANT_ID_NUMBER = "8001015800083";
    private static final String POLICY_NUMBER = "POL-CL-1001";
    private static final LocalDate INCIDENT_DATE = LocalDate.of(2026, 8, 1);

    @Mock
    private ClaimRepository claimRepository;
    @Mock
    private ClientRegistryClient clientRegistryClient;
    @Mock
    private PolicyManagerClient policyManagerClient;
    @Mock
    private PaymentClient paymentClient;
    @Mock
    private DuplicateClaimDetector duplicateClaimDetector;
    @Mock
    private ClaimReferenceGenerator claimReferenceGenerator;
    @Mock
    private ProcessedPaymentEventRecorder processedPaymentEventRecorder;

    private ClaimOrchestratorService orchestratorService;

    @BeforeEach
    void setUp() {
        orchestratorService = new ClaimOrchestratorService(
                claimRepository, clientRegistryClient, policyManagerClient, paymentClient,
                duplicateClaimDetector, claimReferenceGenerator, processedPaymentEventRecorder);
        // save() just returns whatever it was given, like a real repository would after a flush.
        // lenient: a couple of tests (missing/wrong-state claim) never reach save() at all.
        org.mockito.Mockito.lenient().when(claimRepository.save(any(Claim.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        // lenient: only tests that reach POLICY_VALIDATED exercise the duplicate check.
        org.mockito.Mockito.lenient().when(duplicateClaimDetector.findPossibleDuplicate(any()))
                .thenReturn(Optional.empty());
        // lenient: only reached when submitClaim() actually creates a new claim.
        org.mockito.Mockito.lenient().when(claimReferenceGenerator.next()).thenReturn("CLM-000001");
        // lenient: only tests that call applyPaymentOutcome() exercise the webhook idempotency check.
        org.mockito.Mockito.lenient().when(processedPaymentEventRecorder.recordIfNew(any()))
                .thenReturn(true);
    }

    private ClaimSubmissionRequest validRequest() {
        return new ClaimSubmissionRequest(
                CLIENT_ID, CLAIMANT_NAME, CLAIMANT_ID_NUMBER, POLICY_NUMBER,
                ClaimType.DEATH, INCIDENT_DATE, new BigDecimal("100000.00"));
    }

    private void stubActiveMatchingClient() {
        when(clientRegistryClient.validateClient(any())).thenReturn(ClientValidationResult.pass());
    }

    private void stubActiveCoveringPolicy() {
        when(policyManagerClient.validatePolicy(any())).thenReturn(PolicyValidationResult.pass());
    }

    @Test
    void submitClaim_happyPath_reachesPendingAnalystApproval() {
        stubActiveMatchingClient();
        stubActiveCoveringPolicy();

        ClaimResponse response = orchestratorService.submitClaim(validRequest(), null);

        assertThat(response.status()).isEqualTo(ClaimStatus.PENDING_ANALYST_APPROVAL);
        assertThat(response.rejectionReason()).isNull();
    }

    @Test
    void submitClaim_deathClaim_isHighPriority() {
        stubActiveMatchingClient();
        stubActiveCoveringPolicy();

        ClaimResponse response = orchestratorService.submitClaim(validRequest(), null);

        assertThat(response.priority()).isEqualTo(com.insurer.claims.entity.ClaimPriority.HIGH);
    }

    @Test
    void submitClaim_inactiveClient_isRejectedBeforePolicyIsEvenChecked() {
        when(clientRegistryClient.validateClient(any()))
                .thenReturn(ClientValidationResult.invalid("Client %s is not active".formatted(CLIENT_ID)));

        ClaimResponse response = orchestratorService.submitClaim(validRequest(), null);

        assertThat(response.status()).isEqualTo(ClaimStatus.REJECTED);
        assertThat(response.rejectionReason()).contains("not active");
        org.mockito.Mockito.verifyNoInteractions(policyManagerClient);
    }

    @Test
    void submitClaim_claimantNameMismatch_isRejected() {
        when(clientRegistryClient.validateClient(any()))
                .thenReturn(ClientValidationResult.invalid("Submitted claimant name does not match the Client Registry record"));

        ClaimResponse response = orchestratorService.submitClaim(validRequest(), null);

        assertThat(response.status()).isEqualTo(ClaimStatus.REJECTED);
        assertThat(response.rejectionReason()).contains("name does not match");
    }

    @Test
    void submitClaim_policyNotActive_isRejected() {
        stubActiveMatchingClient();
        when(policyManagerClient.validatePolicy(any()))
                .thenReturn(PolicyValidationResult.invalid("Policy %s is not active".formatted(POLICY_NUMBER)));

        ClaimResponse response = orchestratorService.submitClaim(validRequest(), null);

        assertThat(response.status()).isEqualTo(ClaimStatus.REJECTED);
        assertThat(response.rejectionReason()).contains("not active");
    }

    @Test
    void submitClaim_wrongPolicyholder_isRejected() {
        stubActiveMatchingClient();
        when(policyManagerClient.validatePolicy(any()))
                .thenReturn(PolicyValidationResult.invalid("Claimant is not the policyholder on record for this policy"));

        ClaimResponse response = orchestratorService.submitClaim(validRequest(), null);

        assertThat(response.status()).isEqualTo(ClaimStatus.REJECTED);
        assertThat(response.rejectionReason()).contains("policyholder");
    }

    @Test
    void submitClaim_amountExceedsBenefitLimit_isRejected() {
        stubActiveMatchingClient();
        when(policyManagerClient.validatePolicy(any()))
                .thenReturn(PolicyValidationResult.invalid("Claimed amount exceeds the policy benefit limit"));

        ClaimResponse response = orchestratorService.submitClaim(validRequest(), null);

        assertThat(response.status()).isEqualTo(ClaimStatus.REJECTED);
        assertThat(response.rejectionReason()).contains("benefit limit");
    }

    @Test
    void approveClaim_whenPaymentAccepted_movesToPaymentRequested() {
        UUID claimId = UUID.randomUUID();
        Claim pendingClaim = Claim.submit("CLM-000001", CLIENT_ID, CLAIMANT_NAME, CLAIMANT_ID_NUMBER, POLICY_NUMBER,
                ClaimType.DEATH, INCIDENT_DATE, new BigDecimal("100000.00"), null);
        pendingClaim.transitionTo(ClaimStatus.CLIENT_VALIDATED);
        pendingClaim.transitionTo(ClaimStatus.POLICY_VALIDATED);
        pendingClaim.transitionTo(ClaimStatus.PENDING_ANALYST_APPROVAL);

        when(claimRepository.findById(claimId)).thenReturn(Optional.of(pendingClaim));
        when(paymentClient.requestPayment(any(), any(), any()))
                .thenReturn(new PaymentRequestAcknowledgement("PROVIDER-REQ-1", true, "Accepted"));

        ClaimResponse response = orchestratorService.approveClaim(claimId, "analyst-1");

        assertThat(response.status()).isEqualTo(ClaimStatus.PAYMENT_REQUESTED);
    }

    @Test
    void approveClaim_whenPaymentDeclined_rejectsClaim() {
        UUID claimId = UUID.randomUUID();
        Claim pendingClaim = Claim.submit("CLM-000001", CLIENT_ID, CLAIMANT_NAME, CLAIMANT_ID_NUMBER, POLICY_NUMBER,
                ClaimType.DEATH, INCIDENT_DATE, new BigDecimal("100000.00"), null);
        pendingClaim.transitionTo(ClaimStatus.CLIENT_VALIDATED);
        pendingClaim.transitionTo(ClaimStatus.POLICY_VALIDATED);
        pendingClaim.transitionTo(ClaimStatus.PENDING_ANALYST_APPROVAL);

        when(claimRepository.findById(claimId)).thenReturn(Optional.of(pendingClaim));
        when(paymentClient.requestPayment(any(), any(), any()))
                .thenReturn(new PaymentRequestAcknowledgement(null, false, "Insufficient provider balance"));

        ClaimResponse response = orchestratorService.approveClaim(claimId, "analyst-1");

        assertThat(response.status()).isEqualTo(ClaimStatus.REJECTED);
        assertThat(response.rejectionReason()).contains("Payment System declined");
    }

    @Test
    void approveClaim_whenNotPendingApproval_throws() {
        UUID claimId = UUID.randomUUID();
        Claim freshClaim = Claim.submit("CLM-000001", CLIENT_ID, CLAIMANT_NAME, CLAIMANT_ID_NUMBER, POLICY_NUMBER,
                ClaimType.DEATH, INCIDENT_DATE, new BigDecimal("100000.00"), null); // still SUBMITTED

        when(claimRepository.findById(claimId)).thenReturn(Optional.of(freshClaim));

        assertThatThrownBy(() -> orchestratorService.approveClaim(claimId, "analyst-1"))
                .isInstanceOf(InvalidClaimStateException.class);
    }

    @Test
    void approveClaim_whenClaimMissing_throwsNotFound() {
        UUID claimId = UUID.randomUUID();
        when(claimRepository.findById(claimId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orchestratorService.approveClaim(claimId, "analyst-1"))
                .isInstanceOf(ClaimNotFoundException.class);
    }

    @Test
    void applyPaymentOutcome_successful_marksClaimPaid() {
        UUID claimId = UUID.randomUUID();
        Claim requestedClaim = Claim.submit("CLM-000001", CLIENT_ID, CLAIMANT_NAME, CLAIMANT_ID_NUMBER, POLICY_NUMBER,
                ClaimType.DEATH, INCIDENT_DATE, new BigDecimal("100000.00"), null);
        requestedClaim.transitionTo(ClaimStatus.CLIENT_VALIDATED);
        requestedClaim.transitionTo(ClaimStatus.POLICY_VALIDATED);
        requestedClaim.transitionTo(ClaimStatus.PENDING_ANALYST_APPROVAL);
        requestedClaim.transitionTo(ClaimStatus.PAYMENT_REQUESTED);

        when(claimRepository.findById(claimId)).thenReturn(Optional.of(requestedClaim));

        orchestratorService.applyPaymentOutcome(claimId, "evt-1", PaymentOutcome.SUCCESSFUL, "PROV-REF-1");

        assertThat(requestedClaim.getStatus()).isEqualTo(ClaimStatus.PAID);
        assertThat(requestedClaim.getPaymentReference()).isEqualTo("PROV-REF-1");
    }

    @Test
    void applyPaymentOutcome_redeliveredEvent_isIgnoredSecondTime() {
        UUID claimId = UUID.randomUUID();
        Claim requestedClaim = Claim.submit("CLM-000001", CLIENT_ID, CLAIMANT_NAME, CLAIMANT_ID_NUMBER, POLICY_NUMBER,
                ClaimType.DEATH, INCIDENT_DATE, new BigDecimal("100000.00"), null);
        requestedClaim.transitionTo(ClaimStatus.CLIENT_VALIDATED);
        requestedClaim.transitionTo(ClaimStatus.POLICY_VALIDATED);
        requestedClaim.transitionTo(ClaimStatus.PENDING_ANALYST_APPROVAL);
        requestedClaim.transitionTo(ClaimStatus.PAYMENT_REQUESTED);

        when(claimRepository.findById(claimId)).thenReturn(Optional.of(requestedClaim));
        // First call: not yet processed. Second call (the redelivery): the DB-backed check now finds it.
        when(processedPaymentEventRecorder.recordIfNew("evt-1")).thenReturn(true, false);

        orchestratorService.applyPaymentOutcome(claimId, "evt-1", PaymentOutcome.SUCCESSFUL, "PROV-REF-1");
        // Redelivery of the same event: must not throw (claim is already PAID, a naive
        // second transitionTo(PAID) would be illegal) and must not double-apply anything.
        orchestratorService.applyPaymentOutcome(claimId, "evt-1", PaymentOutcome.SUCCESSFUL, "PROV-REF-1");

        assertThat(requestedClaim.getStatus()).isEqualTo(ClaimStatus.PAID);
    }

    @Test
    void submitClaim_repeatedIdempotencyKey_returnsExistingClaimWithoutResubmitting() {
        Claim existingClaim = Claim.submit("CLM-000001", CLIENT_ID, CLAIMANT_NAME, CLAIMANT_ID_NUMBER, POLICY_NUMBER,
                ClaimType.DEATH, INCIDENT_DATE, new BigDecimal("100000.00"), "idem-key-1");
        existingClaim.transitionTo(ClaimStatus.CLIENT_VALIDATED);
        existingClaim.transitionTo(ClaimStatus.POLICY_VALIDATED);
        existingClaim.transitionTo(ClaimStatus.PENDING_ANALYST_APPROVAL);

        when(claimRepository.findByIdempotencyKey("idem-key-1")).thenReturn(Optional.of(existingClaim));

        ClaimSubmissionRequest retriedRequest = new ClaimSubmissionRequest(
                CLIENT_ID, CLAIMANT_NAME, CLAIMANT_ID_NUMBER, POLICY_NUMBER,
                ClaimType.DEATH, INCIDENT_DATE, new BigDecimal("100000.00"));

        ClaimResponse response = orchestratorService.submitClaim(retriedRequest, "idem-key-1");

        assertThat(response.id()).isEqualTo(existingClaim.getId());
        assertThat(response.status()).isEqualTo(ClaimStatus.PENDING_ANALYST_APPROVAL);
        org.mockito.Mockito.verifyNoInteractions(clientRegistryClient, policyManagerClient, duplicateClaimDetector, claimReferenceGenerator);
        verify(claimRepository, never()).save(any());
    }

    @Test
    void submitClaim_possibleDuplicate_isFlaggedButStillReachesAnalystQueue() {
        stubActiveMatchingClient();
        stubActiveCoveringPolicy();
        UUID otherClaimId = UUID.randomUUID();
        when(duplicateClaimDetector.findPossibleDuplicate(any())).thenReturn(Optional.of(otherClaimId));

        ClaimResponse response = orchestratorService.submitClaim(validRequest(), null);

        assertThat(response.status()).isEqualTo(ClaimStatus.PENDING_ANALYST_APPROVAL);
        assertThat(response.possibleDuplicateOfClaimId()).isEqualTo(otherClaimId);
    }
}
