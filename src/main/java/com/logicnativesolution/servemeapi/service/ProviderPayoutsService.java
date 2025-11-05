package com.logicnativesolution.servemeapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.logicnativesolution.servemeapi.config.PaystackConfig;
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
import java.util.Map;

/**
 * Minimal provider payouts execution service using Paystack Transfers.
 * Flow:
 *  - Admin/operator approves a withdrawal → status "approved".
 *  - Operator triggers processing → we create a Paystack transfer and persist a mapping paystackTransfers/{reference}.
 *  - Webhook (transfer.success/failed) reconciles to update the withdrawal status accordingly.
 */
@Service
@RequiredArgsConstructor
public class ProviderPayoutsService {

    private final PaystackConfig paystackConfig;
    private final FirestoreService firestoreService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public Map<String, Object> approveWithdrawal(String uid, String withdrawalId) {
        try {
            Map<String, Object> update = new HashMap<>();
            update.put("status", "approved");
            update.put("approvedAt", Instant.now().toString());
            firestoreService.setInSubcollection("providers", uid, "withdrawals", withdrawalId, update);
            Map<String, Object> out = new HashMap<>();
            out.put("status", "approved");
            out.put("_httpStatus", HttpStatus.OK.value());
            return out;
        } catch (Exception e) {
            return Map.of("_httpStatus", HttpStatus.INTERNAL_SERVER_ERROR.value(), "error", "Failed to approve withdrawal");
        }
    }

    public Map<String, Object> processWithdrawal(String uid, String withdrawalId) {
        if (paystackConfig.getSecretKey() == null) {
            return Map.of(
                    "_httpStatus", HttpStatus.SERVICE_UNAVAILABLE.value(),
                    "error", "Paystack not configured",
                    "message", "Set PAYSTACK_SECRET_KEY or app.paystack.secretKey"
            );
        }
        try {
            // Load withdrawal
            Object wSnap = firestoreService.getFromSubcollection("providers", uid, "withdrawals", withdrawalId);
            Class<?> docSnapClass = Class.forName("com.google.cloud.firestore.DocumentSnapshot");
            Boolean exists = (Boolean) docSnapClass.getMethod("exists").invoke(wSnap);
            if (!Boolean.TRUE.equals(exists)) {
                return Map.of("_httpStatus", HttpStatus.NOT_FOUND.value(), "error", "withdrawal_not_found");
            }
            @SuppressWarnings("unchecked") Map<String, Object> withdrawal = (Map<String, Object>) docSnapClass.getMethod("getData").invoke(wSnap);
            if (withdrawal == null) withdrawal = new HashMap<>();
            String status = String.valueOf(withdrawal.getOrDefault("status", "queued"));
            if (!status.equals("approved") && !status.equals("queued")) {
                return Map.of("_httpStatus", HttpStatus.BAD_REQUEST.value(), "error", "invalid_status_for_processing", "status", status);
            }
            // Load provider's settlement info (subaccount preferred)
            Object pSnap = firestoreService.get("providers", uid);
            Boolean pExists = (Boolean) docSnapClass.getMethod("exists").invoke(pSnap);
            if (!Boolean.TRUE.equals(pExists)) {
                return Map.of("_httpStatus", HttpStatus.BAD_REQUEST.value(), "error", "provider_missing");
            }
            @SuppressWarnings("unchecked") Map<String, Object> provider = (Map<String, Object>) docSnapClass.getMethod("getData").invoke(pSnap);
            @SuppressWarnings("unchecked") Map<String, Object> paystack = provider != null ? (Map<String, Object>) provider.get("paystack") : null;
            String subaccountCode = paystack != null ? (String) paystack.get("subaccountCode") : null;

            // Build transfer payload: If subaccount exists, use transfer to subaccount via split; otherwise require recipient code handling (not implemented).
            Long amount = tryParseLong(withdrawal.get("amount"));
            if (amount == null || amount <= 0) {
                return Map.of("_httpStatus", HttpStatus.BAD_REQUEST.value(), "error", "invalid_amount");
            }
            String currency = String.valueOf(withdrawal.getOrDefault("currency", "ZAR"));

            Map<String, Object> payload = new HashMap<>();
            payload.put("amount", amount);
            payload.put("currency", currency);
            // We use transfer to subaccount by specifying the subaccount in a split object (Paystack supports transaction splits; for transfers, we may need recipient_code).
            // For MVP, if subaccountCode is present, we set a narration and proceed. Otherwise we bail with a helpful error.
            if (!StringUtils.hasText(subaccountCode)) {
                return Map.of("_httpStatus", HttpStatus.BAD_REQUEST.value(), "error", "subaccount_required", "message", "Link Paystack subaccount first during provider onboarding");
            }
            payload.put("reason", "ServeMe provider withdrawal " + withdrawalId);
            // Use metadata to carry our mapping for webhook reconciliation
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("purpose", "provider_withdrawal");
            metadata.put("uid", uid);
            metadata.put("withdrawalId", withdrawalId);
            payload.put("metadata", metadata);
            // For Paystack transfers, we need a recipient code; in SA this may require creating a transfer recipient.
            // MVP simplification: invoke transfer to subaccount via /transfer with source=balance and put subaccount as a note (not officially supported for direct transfer). Real implementation should use recipient codes.
            payload.put("source", "balance");

            String body = objectMapper.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(paystackConfig.getBaseUrl() + "/transfer"))
                    .header("Authorization", paystackConfig.getAuthHeader())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                JsonNode root = objectMapper.readTree(resp.body());
                boolean ok = root.path("status").asBoolean(false);
                if (!ok) {
                    return Map.of("_httpStatus", HttpStatus.BAD_GATEWAY.value(), "error", true, "stage", "transfer_init", "message", root.path("message").asText("Paystack transfer failed"));
                }
                String reference = root.path("data").path("reference").asText(null);
                if (!StringUtils.hasText(reference)) reference = root.path("data").path("transfer_code").asText(null);
                // Persist mapping for webhook reconciliation
                Map<String, Object> map = new HashMap<>();
                map.put("purpose", "provider_withdrawal");
                map.put("uid", uid);
                map.put("withdrawalId", withdrawalId);
                map.put("createdAt", Instant.now().toString());
                firestoreService.set("paystackTransfers", reference, map);

                // Mark withdrawal as processing
                Map<String, Object> update = new HashMap<>();
                update.put("status", "processing");
                update.put("reference", reference);
                update.put("updatedAt", Instant.now().toString());
                firestoreService.setInSubcollection("providers", uid, "withdrawals", withdrawalId, update);

                Map<String, Object> out = new HashMap<>();
                out.put("status", "processing");
                out.put("reference", reference);
                out.put("_httpStatus", HttpStatus.ACCEPTED.value());
                return out;
            }
            return Map.of("_httpStatus", HttpStatus.BAD_GATEWAY.value(), "error", true, "status", resp.statusCode());
        } catch (ClassNotFoundException e) {
            return Map.of("_httpStatus", HttpStatus.SERVICE_UNAVAILABLE.value(), "error", "Firebase SDK not available");
        } catch (Exception e) {
            return Map.of("_httpStatus", HttpStatus.INTERNAL_SERVER_ERROR.value(), "error", "Failed to process withdrawal");
        }
    }

    private static Long tryParseLong(Object o) {
        if (o == null) return null;
        try { return Long.parseLong(String.valueOf(o)); } catch (Exception e) { return null; }
    }
}
