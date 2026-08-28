package com.insurer.claims.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insurer.claims.dto.ApproveClaimRequest;
import com.insurer.claims.dto.ClaimSubmissionRequest;
import com.insurer.claims.dto.PaymentCallbackRequest;
import com.insurer.claims.dto.PaymentOutcome;
import com.insurer.claims.entity.ClaimType;
import com.insurer.claims.service.PaymentWebhookSignatureVerifier;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end through the real Spring context (H2 in-memory, the Mock*Client
 * beans standing in for the three external systems) - exercises the whole
 * claim lifecycle exactly as described in the Claim Sequence diagram:
 * submit -> validate -> approve -> pay -> webhook.
 *
 * <p>Uses the {@code MockClientRegistryClient}/{@code MockPolicyManagerClient}
 * demo conventions documented on those classes: policyNumber
 * {@code "POL-" + clientId}, claimantFullName {@code "Registered Client " + clientId},
 * claimantIdNumber {@code "ID-" + clientId}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ClaimsWorkflowIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private PaymentWebhookSignatureVerifier signatureVerifier;

    @Test
    void fullClaimLifecycle_submitApprovePay() throws Exception {
        String clientId = "CL-2001";
        ClaimSubmissionRequest submission = new ClaimSubmissionRequest(
                clientId,
                "Registered Client " + clientId,
                "ID-" + clientId,
                "POL-" + clientId,
                ClaimType.DEATH,
                LocalDate.of(2026, 8, 1),
                new BigDecimal("75000.00"));

        String submitResponse = mockMvc.perform(post("/claims/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(submission)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_ANALYST_APPROVAL"))
                .andExpect(jsonPath("$.priority").value("HIGH"))
                .andReturn().getResponse().getContentAsString();

        String claimId = JsonPath.read(submitResponse, "$.id");

        // the analyst's queue should contain it
        mockMvc.perform(get("/claims/queue"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(claimId));

        // approve -> Payment System (mock) accepts -> PAYMENT_REQUESTED
        mockMvc.perform(post("/claims/{id}/approve", claimId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ApproveClaimRequest("analyst-1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAYMENT_REQUESTED"));

        // Payment System's webhook confirms payment
        PaymentCallbackRequest callback = new PaymentCallbackRequest(
                "evt-" + UUID.randomUUID(), UUID.fromString(claimId), PaymentOutcome.SUCCESSFUL, "PROV-REF-9");
        String signature = signatureVerifier.sign(callback);

        mockMvc.perform(post("/claims/{id}/payment-callback", claimId)
                        .header("X-Payment-Signature", signature)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(callback)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/claims/{id}", claimId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.paymentReference").value("PROV-REF-9"));
    }

    @Test
    void submitClaim_missingRequiredField_returns400() throws Exception {
        String badJson = """
                {"clientId":"","claimantFullName":"","claimantIdNumber":"","policyNumber":"",
                 "claimType":"DEATH","claimedAmount":100}
                """;

        mockMvc.perform(post("/claims/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(badJson))
                .andExpect(status().isBadRequest());
    }

    @Test
    void submitClaim_inactiveClient_isRejectedImmediately() throws Exception {
        String clientId = "INACTIVE-CL-9001";
        ClaimSubmissionRequest submission = new ClaimSubmissionRequest(
                clientId, "Whoever", "Whatever", "POL-" + clientId, ClaimType.MEDICAL,
                LocalDate.of(2026, 8, 1), new BigDecimal("500.00"));

        mockMvc.perform(post("/claims/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(submission)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectionReason").value(org.hamcrest.Matchers.containsString("not active")));
    }

    @Test
    void paymentCallback_wrongSignature_returns401() throws Exception {
        String clientId = "CL-3001";
        ClaimSubmissionRequest submission = new ClaimSubmissionRequest(
                clientId, "Registered Client " + clientId, "ID-" + clientId,
                "POL-" + clientId, ClaimType.MEDICAL, LocalDate.of(2026, 8, 1), new BigDecimal("500.00"));

        String submitResponse = mockMvc.perform(post("/claims/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(submission)))
                .andReturn().getResponse().getContentAsString();
        String claimId = JsonPath.read(submitResponse, "$.id");

        mockMvc.perform(post("/claims/{id}/approve", claimId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new ApproveClaimRequest("analyst-1"))));

        PaymentCallbackRequest callback = new PaymentCallbackRequest(
                "evt-bad-sig", UUID.fromString(claimId), PaymentOutcome.SUCCESSFUL, "PROV-REF-X");

        mockMvc.perform(post("/claims/{id}/payment-callback", claimId)
                        .header("X-Payment-Signature", "not-the-real-signature")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(callback)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void submitClaim_repeatedIdempotencyKey_doesNotCreateASecondClaim() throws Exception {
        String clientId = "CL-4001";
        ClaimSubmissionRequest submission = new ClaimSubmissionRequest(
                clientId, "Registered Client " + clientId, "ID-" + clientId, "POL-" + clientId,
                ClaimType.MEDICAL, LocalDate.of(2026, 8, 1), new BigDecimal("500.00"));

        String firstResponse = mockMvc.perform(post("/claims/submit")
                        .header("Idempotency-Key", "channel-ref-abc123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(submission)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String firstClaimId = JsonPath.read(firstResponse, "$.id");

        // Same idempotency key again (e.g. the controller form retried after a network blip).
        String retryResponse = mockMvc.perform(post("/claims/submit")
                        .header("Idempotency-Key", "channel-ref-abc123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(submission)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String retryClaimId = JsonPath.read(retryResponse, "$.id");

        org.assertj.core.api.Assertions.assertThat(retryClaimId).isEqualTo(firstClaimId);

        // Other tests in this class share the same H2 instance and queue, so filter to this client
        // rather than asserting the whole queue's size.
        String queueResponse = mockMvc.perform(get("/claims/queue"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> clientIds = JsonPath.read(queueResponse, "$[*].clientId");
        long matchingEntries = clientIds.stream().filter(id -> id.equals(clientId)).count();
        org.assertj.core.api.Assertions.assertThat(matchingEntries).isEqualTo(1);
    }

    @Test
    void submitClaim_matchingPolicyClaimTypeAndIncidentDate_isFlaggedAsPossibleDuplicate() throws Exception {
        String clientId = "CL-5001";
        ClaimSubmissionRequest submission = new ClaimSubmissionRequest(
                clientId, "Registered Client " + clientId, "ID-" + clientId, "POL-" + clientId,
                ClaimType.MEDICAL, LocalDate.of(2026, 7, 15), new BigDecimal("500.00"));

        String firstResponse = mockMvc.perform(post("/claims/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(submission)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.possibleDuplicateOfClaimId").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String firstClaimId = JsonPath.read(firstResponse, "$.id");

        // Same client/policy/claim type/incident date, no idempotency key - a second, separate submission.
        mockMvc.perform(post("/claims/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(submission)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_ANALYST_APPROVAL"))
                .andExpect(jsonPath("$.possibleDuplicateOfClaimId").value(firstClaimId));
    }
}
