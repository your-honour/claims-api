package com.insurer.claims.client;

import com.insurer.claims.client.dto.PolicyValidationRequest;
import com.insurer.claims.client.dto.PolicyValidationResult;

/**
 * Adapter for the Policy Manager System.
 *
 * <p>Named {@code validatePolicy}, returning a verdict rather than a data
 * record: the brief states claim validations/checks relating to plans,
 * policies and benefits are handled by the Policy Manager System, so that
 * comparison belongs on that side of the boundary, not in the Claims
 * System's orchestrator.
 */
public interface PolicyManagerClient {

    PolicyValidationResult validatePolicy(PolicyValidationRequest request);
}
