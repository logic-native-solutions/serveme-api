package com.logicnativesolution.servemeapi.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.logicnativesolution.servemeapi.config.PaystackConfig;
import com.logicnativesolution.servemeapi.service.FirestoreService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Job-centric payment endpoints aligned to the Paystack architecture doc.
 *
 * Endpoints
 * - POST /api/v1/jobs/{jobId}/prepare-payment { uid, paymentMethodId }
 * - POST /api/v1/jobs/{jobId}/charge { uid }
 *
 * Notes
 * - We assume paymentMethodId maps to a stored Paystack reusable authorization at
 *   clients/{uid}/paymentMethods/{authorization_code} with field authorization_code.
 * - Provider subaccount is read from providers/{providerUid}/paystack.subaccountCode when available.
 */
@RestController
@RequestMapping("/api/v1/jobs")
@RequiredArgsConstructor
public class JobPaymentsController {

    private final FirestoreService firestoreService;
    private final PaystackConfig paystackConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostMapping("/{jobId}/prepare-payment")
    public ResponseEntity<?> prepare(@PathVariable String jobId, @RequestBody PreparePaymentRequest req) {
        if (!StringUtils.hasText(jobId)) return ResponseEntity.badRequest().body(Map.of("error", "jobId is required"));
        if (req == null || !StringUtils.hasText(req.getUid()) || !StringUtils.hasText(req.getPaymentMethodId())) {
            return ResponseEntity.badRequest().body(Map.of("error", "uid and paymentMethodId are required"));
        }
        try {
            // Validate payment method exists for user
            Object pmSnap = firestoreService.getFromSubcollection("clients", req.getUid(), "paymentMethods", req.getPaymentMethodId());
            boolean pmExists = false;
            Map<String, Object> pmData = new HashMap<>();
            if (pmSnap != null) {
                try {
                    Class<?> docClass = Class.forName("com.google.cloud.firestore.DocumentSnapshot");
                    Boolean exists = (Boolean) docClass.getMethod("exists").invoke(pmSnap);
                    pmExists = Boolean.TRUE.equals(exists);
                    if (pmExists) {
                        @SuppressWarnings("unchecked") Map<String, Object> data = (Map<String, Object>) docClass.getMethod("getData").invoke(pmSnap);
                        if (data != null) pmData.putAll(data);
                    }
                } catch (Throwable ignore) {}
            }
            if (!pmExists) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "paymentMethodId not found for user"));
            }

            // Read job to attach selection and find provider if already assigned
            Object jobSnap = firestoreService.get("jobs", jobId);
            String providerUid = null;
            String currency = "ZAR";
            Long subtotal = null;
            if (jobSnap != null) {
                try {
                    Class<?> docClass = Class.forName("com.google.cloud.firestore.DocumentSnapshot");
                    Boolean exists = (Boolean) docClass.getMethod("exists").invoke(jobSnap);
                    if (Boolean.TRUE.equals(exists)) {
                        @SuppressWarnings("unchecked") Map<String, Object> data = (Map<String, Object>) docClass.getMethod("getData").invoke(jobSnap);
                        if (data != null) {
                            Object ap = data.get("assignedProviderId");
                            if (ap != null) providerUid = String.valueOf(ap);
                            Object price = data.get("price");
                            if (price instanceof Map<?,?> p) {
                                Object cur = p.get("currency");
                                if (cur != null) currency = String.valueOf(cur);
                                Object sub = p.get("subtotal");
                                if (sub instanceof Number n) subtotal = n.longValue();
                            }
                        }
                    }
                } catch (Throwable ignore) {}
            }

            Map<String, Object> selection = new HashMap<>();
            selection.put("paymentMethodId", req.getPaymentMethodId());
            selection.put("authorization_code", pmData.getOrDefault("authorization_code", req.getPaymentMethodId()));
            selection.put("brand", pmData.get("brand"));
            selection.put("last4", pmData.get("last4"));
            selection.put("currency", currency);
            if (subtotal != null) selection.put("amount", subtotal);

            // Resolve provider subaccount if provider assigned
            String providerSubaccount = null;
            if (StringUtils.hasText(providerUid)) {
                Object provSnap = firestoreService.get("providers", providerUid);
                if (provSnap != null) {
                    try {
                        Class<?> docClass = Class.forName("com.google.cloud.firestore.DocumentSnapshot");
                        Boolean exists = (Boolean) docClass.getMethod("exists").invoke(provSnap);
                        if (Boolean.TRUE.equals(exists)) {
                            @SuppressWarnings("unchecked") Map<String, Object> data = (Map<String, Object>) docClass.getMethod("getData").invoke(provSnap);
                            if (data != null) {
                                Object ps = data.get("paystack");
                                if (ps instanceof Map<?,?> m) {
                                    Object sac = m.get("subaccountCode");
                                    if (sac != null) providerSubaccount = String.valueOf(sac);
                                }
                            }
                        }
                    } catch (Throwable ignore) {}
                }
            }

            Map<String, Object> update = new HashMap<>();
            Map<String, Object> payment = new HashMap<>();
            payment.put("selection", selection);
            if (StringUtils.hasText(providerSubaccount)) payment.put("providerSubaccount", providerSubaccount);
            update.put("payment", payment);
            update.put("updatedAt", Instant.now().toString());
            firestoreService.set("jobs", jobId, update);

            Map<String, Object> out = new HashMap<>();
            out.put("status", "ready");
            out.put("jobId", jobId);
            out.put("currency", currency);
            if (subtotal != null) out.put("amount", subtotal);
            if (StringUtils.hasText(providerSubaccount)) out.put("providerSubaccount", providerSubaccount);
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Failed to prepare payment"));
        }
    }

    @PostMapping("/{jobId}/charge")
    public ResponseEntity<?> charge(@PathVariable String jobId, @RequestBody ChargeJobRequest req) {
        if (paystackConfig.getSecretKey() == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "error", "Paystack not configured",
                    "message", "Set PAYSTACK_SECRET_KEY or app.paystack.secretKey"
            ));
        }
        if (!StringUtils.hasText(jobId) || req == null || !StringUtils.hasText(req.getUid())) {
            return ResponseEntity.badRequest().body(Map.of("error", "jobId and uid are required"));
        }
        try {
            // Load job and selection
            Object jobSnap = firestoreService.get("jobs", jobId);
            String uid = req.getUid();
            String currency = "ZAR";
            Long amount = null;
            String authorizationCode = null;
            String email = null;
            String providerSubaccount = null;
            if (jobSnap != null) {
                try {
                    Class<?> docClass = Class.forName("com.google.cloud.firestore.DocumentSnapshot");
                    Boolean exists = (Boolean) docClass.getMethod("exists").invoke(jobSnap);
                    if (Boolean.TRUE.equals(exists)) {
                        @SuppressWarnings("unchecked") Map<String, Object> data = (Map<String, Object>) docClass.getMethod("getData").invoke(jobSnap);
                        if (data != null) {
                            Object payment = data.get("payment");
                            if (payment instanceof Map<?,?> p) {
                                Object sel = p.get("selection");
                                if (sel instanceof Map<?,?> s) {
                                    Object ac = s.get("authorization_code");
                                    if (ac != null) authorizationCode = String.valueOf(ac);
                                    Object cur = s.get("currency");
                                    if (cur != null) currency = String.valueOf(cur);
                                    Object amt = s.get("amount");
                                    if (amt instanceof Number n) amount = n.longValue();
                                    Object pmid = s.get("paymentMethodId");
                                    if (email == null && pmid != null) {
                                        // try fetch email from PM doc
                                        Object pmSnap = firestoreService.getFromSubcollection("clients", uid, "paymentMethods", String.valueOf(pmid));
                                        try {
                                            Class<?> pmClass = Class.forName("com.google.cloud.firestore.DocumentSnapshot");
                                            Boolean pmExists = (Boolean) pmClass.getMethod("exists").invoke(pmSnap);
                                            if (Boolean.TRUE.equals(pmExists)) {
                                                @SuppressWarnings("unchecked") Map<String, Object> pmData = (Map<String, Object>) pmClass.getMethod("getData").invoke(pmSnap);
                                                if (pmData != null && pmData.get("email") != null) email = String.valueOf(pmData.get("email"));
                                            }
                                        } catch (Throwable ignore) {}
                                    }
                                }
                                Object sac = p.get("providerSubaccount");
                                if (sac != null) providerSubaccount = String.valueOf(sac);
                            }
                            // fallback to job price
                            Object price = data.get("price");
                            if (amount == null && price instanceof Map<?,?> pr) {
                                Object sub = pr.get("subtotal");
                                if (sub instanceof Number n) amount = n.longValue();
                                Object cur = pr.get("currency");
                                if (cur != null) currency = String.valueOf(cur);
                            }
                        }
                    }
                } catch (Throwable ignore) {}
            }
            if (!StringUtils.hasText(authorizationCode)) {
                return ResponseEntity.badRequest().body(Map.of("error", "No payment method selected. Call prepare-payment first."));
            }
            if (amount == null || amount <= 0) {
                return ResponseEntity.badRequest().body(Map.of("error", "Amount unavailable on job"));
            }

            double pct = paystackConfig.getCommissionPercent() != null ? paystackConfig.getCommissionPercent() : 0.0;
            long providerCommission = Math.round(amount * (pct / 100.0));
            long clientFee = Math.round(amount * (pct / 100.0));
            long totalCharge = amount + clientFee;

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("purpose", "job_payment");
            metadata.put("jobId", jobId);
            metadata.put("uid", uid);

            Map<String, Object> payload = new HashMap<>();
            payload.put("amount", totalCharge);
            payload.put("currency", currency);
            if (StringUtils.hasText(email)) payload.put("email", email);
            payload.put("authorization_code", authorizationCode);
            if (StringUtils.hasText(providerSubaccount)) payload.put("subaccount", providerSubaccount);
            if (StringUtils.hasText(providerSubaccount) && providerCommission > 0) {
                payload.put("transaction_charge", providerCommission);
            }
            payload.put("metadata", metadata);

            String body = objectMapper.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(paystackConfig.getBaseUrl() + "/transaction/charge_authorization"))
                    .header("Authorization", paystackConfig.getAuthHeader())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                JsonNode root = objectMapper.readTree(resp.body());
                boolean status = root.path("status").asBoolean(false);
                if (!status) {
                    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                            "error", true,
                            "stage", "charge_authorization",
                            "message", root.path("message").asText("Paystack charge failed")
                    ));
                }
                JsonNode data = root.path("data");
                String reference = data.path("reference").asText(null);

                Map<String, Object> payment = new HashMap<>();
                payment.put("provider", "paystack");
                payment.put("status", data.path("status").asText("processing"));
                payment.put("reference", reference);
                payment.put("amount", amount);
                payment.put("clientFee", clientFee);
                payment.put("providerCommission", providerCommission);
                payment.put("totalCharge", totalCharge);
                payment.put("currency", currency);
                Map<String, Object> jobUpdate = new HashMap<>();
                jobUpdate.put("payment", payment);
                jobUpdate.put("updatedAt", Instant.now().toString());
                firestoreService.set("jobs", jobId, jobUpdate);

                Map<String, Object> out = new HashMap<>();
                out.put("provider", "paystack");
                out.put("reference", reference);
                out.put("status", data.path("status").asText());
                return ResponseEntity.status(HttpStatus.CREATED).body(out);
            }
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", true, "status", resp.statusCode()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Failed to charge job"));
        }
    }

    @Data
    public static class PreparePaymentRequest {
        private String uid;
        private String paymentMethodId;
    }

    @Data
    public static class ChargeJobRequest {
        private String uid; // client uid
    }
}
