package com.insurer.claims.client;

import com.insurer.claims.client.dto.ClientValidationRequest;
import com.insurer.claims.client.dto.ClientValidationResult;

/**
 * Adapter for the Client Registry System (Fig. 01/02/03 in the submission).
 *
 * <p>Named {@code validateClient}, returning a verdict rather than a data
 * record: the brief states client validation is handled by the Client
 * Registry System, so the comparison between the submitted claimant details
 * and its own record belongs on that side of the boundary, not in the
 * Claims System's orchestrator.
 */
public interface ClientRegistryClient {

    ClientValidationResult validateClient(ClientValidationRequest request);
}
