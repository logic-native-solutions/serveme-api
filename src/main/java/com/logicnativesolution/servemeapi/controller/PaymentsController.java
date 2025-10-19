package com.logicnativesolution.servemeapi.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.logicnativesolution.servemeapi.config.PaystackConfig;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Payments endpoints using Paystack.
 * - POST /intent → Initialize a Paystack transaction
 * - POST /{reference}/capture → Verify a Paystack transaction (kept path for backward compatibility)
 */
@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentsController {

    private final PaystackConfig paystackConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostMapping("/intent")
    public ResponseEntity<?> createIntent(@RequestBody CreateIntentRequest req) {
        if (req == null || req.getAmount() == null || req.getAmount() <= 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "amount is required (cents)"));
        }
        if (paystackConfig.getSecretKey() == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "error", "Paystack not configured",
                    "message", "Set PAYSTACK_SECRET_KEY or app.paystack.secretKey"
            ));
        }
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("amount", req.getAmount()); // cents for ZAR, kobo for NGN
            payload.put("currency", (req.getCurrency() == null || req.getCurrency().isBlank()) ? "ZAR" : req.getCurrency().toUpperCase());
            if (req.getEmail() != null && !req.getEmail().isBlank()) payload.put("email", req.getEmail());
            if (req.getReference() != null && !req.getReference().isBlank()) payload.put("reference", req.getReference());
            if (req.getCallbackUrl() != null && !req.getCallbackUrl().isBlank()) payload.put("callback_url", req.getCallbackUrl());
            if (req.getSubaccount() != null && !req.getSubaccount().isBlank()) payload.put("subaccount", req.getSubaccount());
            if (req.getMetadata() != null && !req.getMetadata().isEmpty()) payload.put("metadata", req.getMetadata());

            String body = objectMapper.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(paystackConfig.getBaseUrl() + "/transaction/initialize"))
                    .header("Authorization", paystackConfig.getAuthHeader())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                JsonNode root = objectMapper.readTree(resp.body());
                boolean status = root.path("status").asBoolean(false);
                if (!status) {
                    return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                            "error", true,
                            "stage", "initialize",
                            "message", root.path("message").asText("Paystack initialize failed")
                    ));
                }
                JsonNode data = root.path("data");
                Map<String, Object> out = new HashMap<>();
                out.put("provider", "paystack");
                out.put("authorizationUrl", data.path("authorization_url").asText());
                out.put("accessCode", data.path("access_code").asText());
                out.put("reference", data.path("reference").asText());
                return ResponseEntity.status(HttpStatus.CREATED).body(out);
            }
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", true, "status", resp.statusCode()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Failed to initialize Paystack"));
        }
    }

    // For compatibility we keep the path but interpret {paymentIntentId} as Paystack {reference}
    @PostMapping("/{reference}/capture")
    public ResponseEntity<?> verify(@PathVariable("reference") String reference) {
        if (paystackConfig.getSecretKey() == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "error", "Paystack not configured",
                    "message", "Set PAYSTACK_SECRET_KEY or app.paystack.secretKey"
            ));
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(paystackConfig.getBaseUrl() + "/transaction/verify/" + reference))
                    .header("Authorization", paystackConfig.getAuthHeader())
                    .GET()
                    .build();
            HttpResponse<String> resp = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                JsonNode root = objectMapper.readTree(resp.body());
                boolean status = root.path("status").asBoolean(false);
                if (!status) {
                    return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                            "error", true,
                            "stage", "verify",
                            "message", root.path("message").asText("Paystack verify failed")
                    ));
                }
                JsonNode data = root.path("data");
                Map<String, Object> out = new HashMap<>();
                out.put("provider", "paystack");
                out.put("status", data.path("status").asText());
                out.put("reference", data.path("reference").asText());
                out.put("amount", data.path("amount").asLong());
                out.put("currency", data.path("currency").asText());
                return ResponseEntity.ok(out);
            }
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", true, "status", resp.statusCode()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Failed to verify Paystack transaction"));
        }
    }

    @Data
    public static class CreateIntentRequest {
        private Long amount; // cents (ZAR), kobo (NGN)
        private String currency; // e.g., ZAR
        private String email; // Paystack requires customer email
        private String reference; // optional client-generated reference
        private String callbackUrl; // optional: where Paystack redirects after payment
        private String subaccount; // optional Paystack subaccount code for split payments
        private Map<String, Object> metadata; // optional metadata
    }
}
