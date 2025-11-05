package com.logicnativesolution.servemeapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.logicnativesolution.servemeapi.config.PaystackConfig;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Business logic for Provider payments: card link and withdrawal requests.
 */
@Service
@RequiredArgsConstructor
public class ProviderPaymentsService {

    private final PaystackConfig paystackConfig;
    private final FirestoreService firestoreService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public Map<String, Object> initCardLink(String uid, LinkCardRequest req) {
        if (paystackConfig.getSecretKey() == null) {
            return Map.of(
                    "_httpStatus", HttpStatus.SERVICE_UNAVAILABLE.value(),
                    "error", "Paystack not configured",
                    "message", "Set PAYSTACK_SECRET_KEY or app.paystack.secretKey"
            );
        }
        if (!StringUtils.hasText(uid)) return Map.of("_httpStatus", HttpStatus.BAD_REQUEST.value(), "error", "uid is required");
        try {
            // Try get provider email from users/{uid} if not provided
            String email = req.getEmail();
            if (!StringUtils.hasText(email)) {
                try {
                    Object snap = firestoreService.get("users", uid);
                    Class<?> docSnapClass = Class.forName("com.google.cloud.firestore.DocumentSnapshot");
                    Boolean exists = (Boolean) docSnapClass.getMethod("exists").invoke(snap);
                    if (Boolean.TRUE.equals(exists)) {
                        @SuppressWarnings("unchecked") Map<String, Object> data = (Map<String, Object>) docSnapClass.getMethod("getData").invoke(snap);
                        if (data != null) {
                            Object em = data.get("email");
                            if (em != null) email = String.valueOf(em);
                        }
                    }
                } catch (Throwable ignore) {}
            }
            if (!StringUtils.hasText(email)) {
                return Map.of("_httpStatus", HttpStatus.BAD_REQUEST.value(), "error", "email is required");
            }

            boolean tokenizeOnly = Boolean.TRUE.equals(req.getTokenizeOnly());
            long amount = (req.getAmount() != null) ? req.getAmount() : (tokenizeOnly ? 0L : 100L);
            String currency = StringUtils.hasText(req.getCurrency()) ? req.getCurrency().toUpperCase() : "ZAR";

            Map<String, Object> payload = new HashMap<>();
            payload.put("amount", amount);
            payload.put("currency", currency);
            payload.put("email", email);
            if (StringUtils.hasText(req.getCallbackUrl())) payload.put("callback_url", req.getCallbackUrl());
            payload.put("channels", new String[]{"card"});
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("purpose", "provider_card_link");
            metadata.put("uid", uid);
            metadata.put("tokenizeOnly", tokenizeOnly);
            if (StringUtils.hasText(req.getMode())) metadata.put("mode", req.getMode());
            payload.put("metadata", metadata);

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
                    return Map.of(
                            "_httpStatus", HttpStatus.BAD_REQUEST.value(),
                            "error", true,
                            "stage", "initialize",
                            "message", root.path("message").asText("Paystack initialize failed")
                    );
                }
                JsonNode data = root.path("data");
                Map<String, Object> out = new HashMap<>();
                out.put("provider", "paystack");
                out.put("authorizationUrl", data.path("authorization_url").asText());
                out.put("accessCode", data.path("access_code").asText());
                out.put("reference", data.path("reference").asText());
                out.put("_httpStatus", HttpStatus.CREATED.value());
                return out;
            }
            return Map.of("_httpStatus", HttpStatus.BAD_REQUEST.value(), "error", true, "status", resp.statusCode());
        } catch (Exception e) {
            return Map.of("_httpStatus", HttpStatus.INTERNAL_SERVER_ERROR.value(), "error", "Failed to initialize card link");
        }
    }

    public Map<String, Object> listPaymentMethods(String uid) {
        try {
            List<Map<String, Object>> items = firestoreService.listSubcollection("providers", uid, "paymentMethods");
            return Map.of("items", items);
        } catch (Exception e) {
            return Map.of("_httpStatus", HttpStatus.INTERNAL_SERVER_ERROR.value(), "error", "Failed to list payment methods");
        }
    }

    public Map<String, Object> createWithdrawRequest(String uid, WithdrawRequest req) {
        if (req.getAmount() == null || req.getAmount() <= 0) {
            return Map.of("_httpStatus", HttpStatus.BAD_REQUEST.value(), "error", "amount must be > 0");
        }
        try {
            Map<String, Object> record = new HashMap<>();
            record.put("amount", req.getAmount());
            record.put("currency", StringUtils.hasText(req.getCurrency()) ? req.getCurrency().toUpperCase() : "ZAR");
            record.put("paymentMethodId", req.getPaymentMethodId());
            record.put("note", req.getNote());
            record.put("status", "queued");
            record.put("createdAt", Instant.now().toString());
            // create a doc with auto-id under providers/{uid}/withdrawals
            @SuppressWarnings("unchecked") Map<String, Object> created = firestoreService.addToSubcollection("providers", uid, "withdrawals", record);
            Map<String, Object> out = new HashMap<>();
            out.putAll(created);
            out.put("_httpStatus", HttpStatus.ACCEPTED.value());
            return out;
        } catch (Exception e) {
            return Map.of("_httpStatus", HttpStatus.INTERNAL_SERVER_ERROR.value(), "error", "Failed to create withdrawal request");
        }
    }

    @Data
    public static class LinkCardRequest {
        private String email;
        private Long amount;
        private String currency;
        private Boolean tokenizeOnly;
        private String callbackUrl;
        private String mode;
    }

    @Data
    public static class WithdrawRequest {
        private Long amount; // in minor units
        private String currency;
        private String paymentMethodId; // provider-selected saved card id (authorization code)
        private String note;
    }
}
