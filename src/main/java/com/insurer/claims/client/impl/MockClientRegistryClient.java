package com.insurer.claims.client.impl;

import com.insurer.claims.client.ClientRegistryClient;
import com.insurer.claims.client.dto.ClientValidationRequest;
import com.insurer.claims.client.dto.ClientValidationResult;
import org.springframework.stereotype.Component;

/**
 * Stands in for the real Client Registry System, which this exercise
 * explicitly excludes from scope. Swapping this for a real HTTP client
 * (e.g. a Feign client or RestClient calling the actual registry) means
 * implementing this one interface - nothing else in the codebase changes,
 * which is the entire point of the adapter boundary.
 *
 * <p>Deterministic by convention, so the happy path and the negative paths
 * are both trivial to demonstrate: a {@code clientId} prefixed {@code
 * INACTIVE-} comes back invalid (client not active); otherwise the mock's
 * "on-record" claimant details are {@code "Registered Client " + clientId}
 * / {@code "ID-" + clientId}, and a submission that doesn't match either is
 * rejected.
 */
@Component
public class MockClientRegistryClient implements ClientRegistryClient {

    private static final String INACTIVE_PREFIX = "INACTIVE-";

    @Override
    public ClientValidationResult validateClient(ClientValidationRequest request) {
        String clientId = request.clientId();

        if (clientId.startsWith(INACTIVE_PREFIX)) {
            return ClientValidationResult.invalid("Client %s is not active".formatted(clientId));
        }

        String onRecordFullName = "Registered Client " + clientId;
        String onRecordIdNumber = "ID-" + clientId;

        if (!onRecordFullName.equals(request.claimantFullName())) {
            return ClientValidationResult.invalid("Submitted claimant name does not match the Client Registry record");
        }
        if (!onRecordIdNumber.equals(request.claimantIdNumber())) {
            return ClientValidationResult.invalid("Submitted claimant ID number does not match the Client Registry record");
        }
        return ClientValidationResult.pass();
    }
}
