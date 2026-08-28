package com.insurer.claims.service;

import com.insurer.claims.dto.PaymentCallbackRequest;
import com.insurer.claims.exception.InvalidWebhookSignatureException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Verifies the {@code X-Payment-Signature} header on inbound payment
 * webhooks (HMAC-SHA256 over a canonical payload string) before anything in
 * the request body is trusted - never trust a webhook payload just because
 * it arrived on the right URL.
 *
 * <p>Simplification worth noting: real payment providers (Stripe, PayGate,
 * Adyen...) sign the exact raw request body bytes, so the receiver hashes
 * the untouched body rather than reconstructing a canonical string from
 * parsed fields the way this sample does. This sample signs a canonical
 * string instead purely to keep the demo self-contained (no separate raw-
 * body-capturing filter); a production implementation should sign/verify
 * the raw bytes to avoid any canonicalisation mismatch.
 */
@Component
@Slf4j
public class PaymentWebhookSignatureVerifier {

    private final String secret;

    public PaymentWebhookSignatureVerifier(@Value("${claims.payment.webhook-secret}") String secret) {
        this.secret = secret;
    }

    public void verify(PaymentCallbackRequest callback, String providedSignatureHex) {
        String expectedSignatureHex = sign(canonicalPayload(callback));

        boolean matches = providedSignatureHex != null
                && MessageDigest.isEqual(
                        expectedSignatureHex.getBytes(StandardCharsets.UTF_8),
                        providedSignatureHex.getBytes(StandardCharsets.UTF_8));

        if (!matches) {
            throw new InvalidWebhookSignatureException(
                    "Payment webhook signature verification failed for event " + callback.eventId());
        }
    }

    /** What this sample computes the signature over - see the class-level note above. */
    public String sign(PaymentCallbackRequest callback) {
        return sign(canonicalPayload(callback));
    }

    private String canonicalPayload(PaymentCallbackRequest callback) {
        return callback.eventId() + ":" + callback.claimId() + ":" + callback.outcome();
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(raw);
        } catch (GeneralSecurityException e) {
            log.error("Unable to compute webhook signature", e);
            throw new IllegalStateException("Unable to compute webhook signature", e);
        }
    }
}
