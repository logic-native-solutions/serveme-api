package com.logicnativesolution.servemeapi.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.logicnativesolution.servemeapi.config.PaystackConfig;
import com.logicnativesolution.servemeapi.service.FirestoreService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/webhooks/paystack")
@RequiredArgsConstructor
public class PaystackWebhooksController {
    private static final Logger log = LoggerFactory.getLogger(PaystackWebhooksController.class);

    private final PaystackConfig paystackConfig;
    private final FirestoreService firestoreService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostMapping
    public ResponseEntity<?> handle(@RequestBody String body,
                                    @RequestHeader(name = "x-paystack-signature", required = false) String signature) {
        if (!StringUtils.hasText(body)) return ResponseEntity.badRequest().build();
        String secret = paystackConfig.getSecretKey();
        if (secret == null || secret.isBlank()) {
            log.warn("Paystack webhook received but secret key is not configured");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("paystack not configured");
        }
        if (!verifySignature(secret, body, signature)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("invalid signature");
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            String event = root.path("event").asText(null);
            JsonNode data = root.path("data");
            if (event == null) return ResponseEntity.badRequest().body("missing event");
            switch (event) {
                case "charge.success":
                    String reference = data.path("reference").asText(null);
                    if (reference != null) updateJobPaymentStatusByReference(reference, "succeeded");
                    break;
                case "transfer.success":
                    // Optionally update provider payout status
                    break;
                default:
                    log.info("Unhandled Paystack event: {}", event);
            }
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("Failed to handle Paystack webhook", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    private boolean verifySignature(String secret, String body, String signature) {
        try {
            if (!StringUtils.hasText(signature)) return false;
            Mac sha512 = Mac.getInstance("HmacSHA512");
            sha512.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            byte[] mac = sha512.doFinal(body.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : mac) sb.append(String.format("%02x", b));
            String computed = sb.toString();
            return computed.equalsIgnoreCase(signature.trim());
        } catch (Exception e) {
            log.warn("Failed to verify Paystack signature: {}", e.getMessage());
            return false;
        }
    }

    private void updateJobPaymentStatusByReference(String reference, String status) {
        try {
            List<Map<String, Object>> jobs = firestoreService.listCollection("jobs");
            for (Map<String, Object> j : jobs) {
                Object payment = j.get("payment");
                String ref = null;
                if (payment instanceof Map<?,?> pm) {
                    Object r = pm.get("reference");
                    if (r != null) ref = String.valueOf(r);
                }
                if (reference.equals(ref)) {
                    String jobId = String.valueOf(j.get("id"));
                    Map<String, Object> update = new HashMap<>();
                    Map<String, Object> pay = new HashMap<>();
                    pay.put("reference", reference);
                    pay.put("status", status);
                    update.put("payment", pay);
                    update.put("updatedAt", java.time.Instant.now().toString());
                    firestoreService.set("jobs", jobId, update);
                    log.info("Updated job {} payment status by reference -> {}", jobId, status);
                    break;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to update job by paystack reference {}", reference, e);
        }
    }
}
