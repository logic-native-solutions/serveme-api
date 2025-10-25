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
 * Client onboarding and card-link flow using Paystack.
 *
 * Endpoints
 * - POST /api/v1/clients/{uid}/paystack/customer → Create or update Paystack Customer and persist customer_code.
 * - POST /api/v1/clients/{uid}/paystack/link-card/init → Initialize a small transaction to capture reusable authorization (card link).
 * - GET  /api/v1/clients/{uid}/paystack/payment-methods → List stored payment method docs from subcollection.
 *
 * Notes
 * - We rely on Paystack webhook (charge.success) to persist the authorization and card details into
 *   Firestore at clients/{uid}/paymentMethods/{authorization_code}. See PaystackWebhooksController.
 */
@RestController
@RequestMapping("/api/v1/clients")
@RequiredArgsConstructor
public class ClientPaymentsController {

    private final PaystackConfig paystackConfig;
    private final FirestoreService firestoreService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostMapping("/{uid}/paystack/customer")
    public ResponseEntity<?> createOrUpdateCustomer(@PathVariable String uid, @RequestBody CreateCustomerRequest req) {
        if (!StringUtils.hasText(uid)) return ResponseEntity.badRequest().body(Map.of("error", "uid is required"));
        if (paystackConfig.getSecretKey() == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "error", "Paystack not configured",
                    "message", "Set PAYSTACK_SECRET_KEY or app.paystack.secretKey"
            ));
        }
        try {
            Map<String, Object> payload = new HashMap<>();
            if (StringUtils.hasText(req.getEmail())) payload.put("email", req.getEmail());
            if (StringUtils.hasText(req.getFirstName())) payload.put("first_name", req.getFirstName());
            if (StringUtils.hasText(req.getLastName())) payload.put("last_name", req.getLastName());
            if (StringUtils.hasText(req.getPhone())) payload.put("phone", req.getPhone());
            // Use metadata to link to our uid for traceability in dashboard
            Map<String, Object> md = new HashMap<>();
            md.put("uid", uid);
            payload.put("metadata", md);

            String body = objectMapper.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(paystackConfig.getBaseUrl() + "/customer"))
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
                            "stage", "create_customer",
                            "message", root.path("message").asText("Paystack customer failed")
                    ));
                }
                JsonNode data = root.path("data");
                String customerCode = data.path("customer_code").asText(null);
                Map<String, Object> paystack = new HashMap<>();
                paystack.put("customer", Map.of(
                        "customer_code", customerCode,
                        "email", data.path("email").asText(null),
                        "first_name", data.path("first_name").asText(null),
                        "last_name", data.path("last_name").asText(null)
                ));
                Map<String, Object> update = new HashMap<>();
                update.put("paystack", paystack);
                update.put("updatedAt", Instant.now().toString());
                firestoreService.set("clients", uid, update);
                return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                        "provider", "paystack",
                        "customer_code", customerCode
                ));
            }
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", true, "status", resp.statusCode()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Failed to create Paystack customer"));
        }
    }

    @PostMapping("/{uid}/paystack/link-card/init")
    public ResponseEntity<?> startCardLink(@PathVariable String uid, @RequestBody LinkCardRequest req) {
        if (paystackConfig.getSecretKey() == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "error", "Paystack not configured",
                    "message", "Set PAYSTACK_SECRET_KEY or app.paystack.secretKey"
            ));
        }
        if (!StringUtils.hasText(uid)) return ResponseEntity.badRequest().body(Map.of("error", "uid is required"));
        try {
            // Read stored Paystack customer if present
            Object snap = firestoreService.get("clients", uid);
            String customerCode = null;
            String email = req.getEmail();
            if (snap instanceof Map<?,?> doc) {
                Object ps = doc.get("paystack");
                if (ps instanceof Map<?,?> pm) {
                    Object cust = pm.get("customer");
                    if (cust instanceof Map<?,?> cm) {
                        Object cc = cm.get("customer_code");
                        if (cc != null) customerCode = String.valueOf(cc);
                        if (!StringUtils.hasText(email) && cm.get("email") != null) email = String.valueOf(cm.get("email"));
                    }
                }
            }
            if (!StringUtils.hasText(email)) {
                return ResponseEntity.badRequest().body(Map.of("error", "email is required on first link or set via /paystack/customer"));
            }

            boolean tokenizeOnly = req.getTokenizeOnly() != null && req.getTokenizeOnly();
            Long requestedAmount = req.getAmount();
            long amount;
            if (tokenizeOnly) {
                // Honor pure tokenization: allow amount 0 to avoid ZAR 1 prompt
                amount = (requestedAmount != null && requestedAmount >= 0) ? requestedAmount : 0L;
            } else {
                amount = (requestedAmount != null && requestedAmount > 0) ? requestedAmount : 100L; // minimal amount otherwise
            }
            String currency = StringUtils.hasText(req.getCurrency()) ? req.getCurrency().toUpperCase() : "ZAR";

            Map<String, Object> payload = new HashMap<>();
            payload.put("amount", amount);
            payload.put("currency", currency);
            payload.put("email", email);
            if (StringUtils.hasText(customerCode)) payload.put("customer", customerCode);
            if (StringUtils.hasText(req.getCallbackUrl())) payload.put("callback_url", req.getCallbackUrl());
            // hint to Paystack that this is a card link/tokenization flow
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("purpose", "card_link");
            metadata.put("uid", uid);
            metadata.put("tokenizeOnly", tokenizeOnly);
            if (StringUtils.hasText(req.getMode())) metadata.put("mode", req.getMode());
            payload.put("metadata", metadata);
            // constrain to cards
            payload.put("channels", new String[]{"card"});

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
                    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
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
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", true, "status", resp.statusCode()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Failed to initialize card link"));
        }
    }

    @GetMapping("/{uid}/paystack/payment-methods")
    public ResponseEntity<?> listPaymentMethods(@PathVariable String uid) {
        try {
            var items = firestoreService.listSubcollection("clients", uid, "paymentMethods");
            return ResponseEntity.ok(Map.of("items", items));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Failed to list payment methods"));
        }
    }

    @Data
    public static class CreateCustomerRequest {
        private String email;
        private String firstName;
        private String lastName;
        private String phone;
    }

    @Data
    public static class LinkCardRequest {
        private Long amount; // minimal amount for card auth (kobo/cents). Use 0 when tokenizeOnly=true
        private String currency; // NGN or ZAR
        private String email; // used if customer not created yet
        private String callbackUrl; // optional
        private Boolean tokenizeOnly; // if true, request pure tokenization (no test charge)
        private String mode; // optional hint e.g., 'card_link'
    }

    // Compute a price quote for a client-selected service (amounts in minor units: kobo/cents)
    @PostMapping("/{uid}/quotes")
    public ResponseEntity<?> createQuote(@PathVariable String uid, @RequestBody QuoteRequest req) {
        if (!StringUtils.hasText(uid)) return ResponseEntity.badRequest().body(Map.of("error", "uid is required"));
        if (req == null || req.getBaseAmount() == null || req.getBaseAmount() <= 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "baseAmount is required (minor units)"));
        }
        long addonsTotal = 0L;
        if (req.getAddons() != null) {
            for (Addon a : req.getAddons()) {
                if (a != null && a.getPrice() != null && a.getPrice() > 0) addonsTotal += a.getPrice();
            }
        }
        long subtotal = req.getBaseAmount() + addonsTotal;
        double pct = paystackConfig.getCommissionPercent() != null ? paystackConfig.getCommissionPercent() : 0.0;
        long clientFee = Math.round(subtotal * (pct / 100.0));
        long providerCommission = Math.round(subtotal * (pct / 100.0));
        long totalCharge = subtotal + clientFee;
        String currency = StringUtils.hasText(req.getCurrency()) ? req.getCurrency().toUpperCase() : "ZAR";
        Map<String, Object> out = new HashMap<>();
        out.put("currency", currency);
        out.put("baseAmount", req.getBaseAmount());
        out.put("addonsTotal", addonsTotal);
        out.put("subtotal", subtotal);
        out.put("clientFee", clientFee);
        out.put("providerCommission", providerCommission);
        out.put("totalCharge", totalCharge);
        out.put("commissionPercent", pct);
        return ResponseEntity.ok(out);
    }

    // Charge a saved card (authorization_code) and split with provider subaccount. Stores payment draft on the job.
    @PostMapping("/{uid}/pay/charge")
    public ResponseEntity<?> chargeSavedCard(@PathVariable String uid, @RequestBody ChargeRequest req) {
        if (paystackConfig.getSecretKey() == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "error", "Paystack not configured",
                    "message", "Set PAYSTACK_SECRET_KEY or app.paystack.secretKey"
            ));
        }
        if (!StringUtils.hasText(uid)) return ResponseEntity.badRequest().body(Map.of("error", "uid is required"));
        if (req == null || !StringUtils.hasText(req.getJobId()) || req.getAmount() == null || req.getAmount() <= 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "jobId and amount are required"));
        }
        if (!StringUtils.hasText(req.getAuthorizationCode())) {
            return ResponseEntity.badRequest().body(Map.of("error", "authorizationCode is required"));
        }
        String currency = StringUtils.hasText(req.getCurrency()) ? req.getCurrency().toUpperCase() : "ZAR";
        double pct = paystackConfig.getCommissionPercent() != null ? paystackConfig.getCommissionPercent() : 0.0;
        long providerCommission = Math.round(req.getAmount() * (pct / 100.0));
        long clientFee = Math.round(req.getAmount() * (pct / 100.0));
        long totalCharge = req.getAmount() + clientFee;
        try {
            Map<String, Object> metadata = new HashMap<>();
            if (req.getMetadata() != null) metadata.putAll(req.getMetadata());
            metadata.put("purpose", "job_payment");
            metadata.put("uid", uid);
            metadata.put("jobId", req.getJobId());

            Map<String, Object> payload = new HashMap<>();
            payload.put("amount", totalCharge);
            payload.put("currency", currency);
            if (StringUtils.hasText(req.getEmail())) payload.put("email", req.getEmail());
            payload.put("authorization_code", req.getAuthorizationCode());
            if (StringUtils.hasText(req.getProviderSubaccount())) payload.put("subaccount", req.getProviderSubaccount());
            // Collect platform commission from provider settlement via subaccount split
            if (StringUtils.hasText(req.getProviderSubaccount()) && providerCommission > 0) {
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
                // Persist payment draft on the job for tracking
                Map<String, Object> payment = new HashMap<>();
                payment.put("provider", "paystack");
                payment.put("status", data.path("status").asText("processing"));
                payment.put("reference", reference);
                payment.put("amount", req.getAmount());
                payment.put("clientFee", clientFee);
                payment.put("providerCommission", providerCommission);
                payment.put("totalCharge", totalCharge);
                payment.put("currency", currency);
                Map<String, Object> jobUpdate = new HashMap<>();
                jobUpdate.put("id", req.getJobId());
                jobUpdate.put("clientId", uid);
                jobUpdate.put("payment", payment);
                jobUpdate.put("updatedAt", Instant.now().toString());
                firestoreService.set("jobs", req.getJobId(), jobUpdate);

                Map<String, Object> out = new HashMap<>();
                out.put("provider", "paystack");
                out.put("reference", reference);
                out.put("status", data.path("status").asText());
                return ResponseEntity.status(HttpStatus.CREATED).body(out);
            }
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", true, "status", resp.statusCode()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Failed to charge authorization"));
        }
    }

    @Data
    public static class QuoteRequest {
        private Long baseAmount; // service base price (minor units)
        private java.util.List<Addon> addons; // optional addons
        private String currency; // default ZAR
    }

    @Data
    public static class Addon {
        private String name;
        private Long price; // minor units
    }

    @Data
    public static class ChargeRequest {
        private String jobId;
        private Long amount; // base service price (minor units) without client fee
        private String currency;
        private String email;
        private String authorizationCode;
        private String providerSubaccount; // provider's Paystack subaccount code
        private Map<String, Object> metadata; // optional
    }

    // List Paystack transactions for a client, optionally filter by authorizationCode and date range
    @GetMapping("/{uid}/paystack/transactions")
    public ResponseEntity<?> listClientTransactions(
            @PathVariable String uid,
            @RequestParam(name = "authorizationCode", required = false) String authorizationCode,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "perPage", required = false) Integer perPage,
            @RequestParam(name = "page", required = false) Integer page
    ) {
        if (!StringUtils.hasText(uid)) return ResponseEntity.badRequest().body(Map.of("error", "uid is required"));
        if (paystackConfig.getSecretKey() == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "error", "Paystack not configured",
                    "message", "Set PAYSTACK_SECRET_KEY or app.paystack.secretKey"
            ));
        }
        try {
            // Get customer_code from Firestore
            String customerCode = null;
            Object snap = firestoreService.get("clients", uid);
            if (snap instanceof Map<?,?> doc) {
                Object ps = doc.get("paystack");
                if (ps instanceof Map<?,?> pm) {
                    Object cust = pm.get("customer");
                    if (cust instanceof Map<?,?> cm) {
                        Object cc = cm.get("customer_code");
                        if (cc != null) customerCode = String.valueOf(cc);
                    }
                }
            }
            // Build URL
            StringBuilder url = new StringBuilder(paystackConfig.getBaseUrl()).append("/transaction");
            boolean hasQuery = false;
            if (StringUtils.hasText(customerCode)) { url.append("?customer=").append(customerCode); hasQuery = true; }
            if (StringUtils.hasText(status)) { url.append(hasQuery?"&":"?").append("status=").append(status); hasQuery = true; }
            if (perPage != null && perPage > 0) { url.append(hasQuery?"&":"?").append("perPage=").append(Math.min(perPage, 50)); hasQuery = true; }
            if (page != null && page > 0) { url.append(hasQuery?"&":"?").append("page=").append(page); hasQuery = true; }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url.toString()))
                    .header("Authorization", paystackConfig.getAuthHeader())
                    .GET()
                    .build();
            HttpResponse<String> resp = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", true, "status", resp.statusCode()));
            }
            JsonNode root = objectMapper.readTree(resp.body());
            if (!root.path("status").asBoolean(false)) {
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                        "error", true,
                        "stage", "list_transactions",
                        "message", root.path("message").asText("Paystack list failed")
                ));
            }
            JsonNode data = root.path("data");
            java.util.List<Map<String, Object>> items = new java.util.ArrayList<>();
            for (JsonNode t : data) {
                // If authorizationCode filter provided, apply client-side filter
                String authCode = t.path("authorization").path("authorization_code").asText(null);
                if (StringUtils.hasText(authorizationCode) && !authorizationCode.equals(authCode)) continue;
                Map<String, Object> m = new HashMap<>();
                m.put("status", t.path("status").asText());
                m.put("reference", t.path("reference").asText());
                m.put("amount", t.path("amount").asLong());
                m.put("currency", t.path("currency").asText());
                m.put("channel", t.path("channel").asText());
                m.put("paidAt", t.path("paid_at").asText(null));
                Map<String, Object> auth = new HashMap<>();
                auth.put("authorization_code", authCode);
                auth.put("brand", t.path("authorization").path("brand").asText(null));
                auth.put("last4", t.path("authorization").path("last4").asText(null));
                m.put("authorization", auth);
                items.add(m);
            }
            return ResponseEntity.ok(Map.of("items", items));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Failed to list transactions"));
        }
    }
}
