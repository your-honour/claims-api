package com.insurer.claims;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Claims API.
 *
 * <p>This service is the orchestration layer described in the case study
 * submission: it is the existing "Claims System UI &amp; API" enhanced with
 * the logic needed to call the Client Registry, Policy Manager and Payment
 * System, none of which were previously connected to it.
 *
 * <p>See the accompanying README for how this maps to the C4 diagrams
 * (System Context / Container / Component) and the claim sequence diagram.
 */
@SpringBootApplication
public class ClaimsApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(ClaimsApiApplication.class, args);
    }
}
