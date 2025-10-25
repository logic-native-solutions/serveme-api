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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Payments endpoints using Paystack.
 * - POST /intent → Initialize a Paystack transaction
 * - POST /card-link/init → Initialize a zero/small transaction to link a card (alias)
 * - GET  /wallet/payment-methods → List user's stored card methods (alias)
 * - POST /{reference}/capture → Verify a Paystack transaction (kept path for backward compatibility)
 */
@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentsController {

    private final PaystackConfig paystackConfig;
    private final FirestoreService firestoreService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostMapping("/estimate")
    public ResponseEntity<?> estimate(@RequestBody EstimateRequest req) {
        try {
            if (req == null || (req.getServiceId() == null && (req.getBasePrice() == null))) {
                return ResponseEntity.badRequest().body(Map.of("error", "serviceId or basePrice is required"));
            }
            long base = 0L;
            String currency = req.getCurrency() != null && !req.getCurrency().isBlank() ? req.getCurrency().toUpperCase() : "ZAR";
            if (req.getServiceId() != null) {
                Object doc = firestoreService.get("services", req.getServiceId());
                if (doc != null) {
                    try {
                        Class<?> docClass = Class.forName("com.google.cloud.firestore.DocumentSnapshot");
                        Boolean exists = (Boolean) docClass.getMethod("exists").invoke(doc);
                        if (Boolean.TRUE.equals(exists)) {
                            @SuppressWarnings("unchecked") Map<String, Object> data = (Map<String, Object>) docClass.getMethod("getData").invoke(doc);
                            if (data != null) {
                                Object bp = data.get("basePrice");
                                if (bp instanceof Number n) base = n.longValue();
                                else if (bp instanceof String s) { try { base = (long) Double.parseDouble(s); } catch (Exception ignore) {} }
                                Object cur = data.get("currency");
                                if (cur != null) currency = String.valueOf(cur);
                                // optional addOns lookup by ids
                                if (req.getAddOnIds() != null && !req.getAddOnIds().isEmpty()) {
                                    Object addOns = data.get("addOns");
                                    if (addOns instanceof java.util.List<?> list) {
                                        for (Object o : list) {
                                            if (o instanceof Map<?,?> mm) {
                                                String id = mm.get("id") == null ? null : String.valueOf(mm.get("id"));
                                                if (id != null && req.getAddOnIds().contains(id)) {
                                                    Object p = mm.get("price");
                                                    if (p instanceof Number pn) base += pn.longValue();
                                                    else if (p instanceof String ps) { try { base += (long) Double.parseDouble(ps); } catch (Exception ignore) {} }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } catch (Throwable ignore) {}
                }
            } else if (req.getBasePrice() != null) {
                base = Math.max(0, req.getBasePrice());
            }
            // distance component (optional): perKmCents from request or 0
            long distanceCents = 0L;
            if (req.getDistanceKm() != null && req.getDistanceKm() > 0) {
                long perKm = req.getPerKmCents() != null ? Math.max(0, req.getPerKmCents()) : 0L;
                distanceCents = Math.round(req.getDistanceKm() * perKm);
            }
            long subtotal = base + distanceCents;
            double pct = paystackConfig.getCommissionPercent() != null ? paystackConfig.getCommissionPercent() : 0.0;
            long clientFee = Math.round(subtotal * (pct / 100.0));
            long total = subtotal + clientFee;
            Map<String, Object> out = new HashMap<>();
            out.put("currency", currency);
            out.put("base", base);
            out.put("distance", distanceCents);
            out.put("fees", clientFee);
            out.put("subtotal", subtotal);
            out.put("total", total);
            out.put("commissionPercent", pct);
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Failed to estimate price"));
        }
    }

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

    // Alias to support FE: POST /api/v1/payments/card-link/init
    @PostMapping("/card-link/init")
    public ResponseEntity<?> cardLinkInit(@RequestBody CardLinkInitRequest req) {
        if (paystackConfig.getSecretKey() == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "error", "Paystack not configured",
                    "message", "Set PAYSTACK_SECRET_KEY or app.paystack.secretKey"
            ));
        }
        if (req == null || !StringUtils.hasText(req.getUid())) {
            return ResponseEntity.badRequest().body(Map.of("error", "uid is required"));
        }
        try {
            // Fetch stored customer for uid (optional)
            Object snap = firestoreService.get("clients", req.getUid());
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
                return ResponseEntity.badRequest().body(Map.of("error", "email is required on first link or set via /clients/{uid}/paystack/customer"));
            }
            long amount = (req.getTokenizeOnly() != null && req.getTokenizeOnly()) ? Math.max(0, req.getAmount() == null ? 0 : req.getAmount()) : Math.max(1, req.getAmount() == null ? 100 : req.getAmount());
            String currency = StringUtils.hasText(req.getCurrency()) ? req.getCurrency().toUpperCase() : "ZAR";

            Map<String, Object> payload = new HashMap<>();
            payload.put("amount", amount);
            payload.put("currency", currency);
            payload.put("email", email);
            if (StringUtils.hasText(customerCode)) payload.put("customer", customerCode);
            if (StringUtils.hasText(req.getCallbackUrl())) payload.put("callback_url", req.getCallbackUrl());
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("purpose", "card_link");
            metadata.put("uid", req.getUid());
            metadata.put("tokenizeOnly", req.getTokenizeOnly() != null && req.getTokenizeOnly());
            payload.put("metadata", metadata);
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
                if (!root.path("status").asBoolean(false)) {
                    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                            "error", true,
                            "stage", "initialize",
                            "message", root.path("message").asText("Paystack initialize failed")
                    ));
                }
                JsonNode data = root.path("data");
                String reference = data.path("reference").asText();
                // Persist a lightweight session mapping so we can recover uid/purpose on webhook/session fallback
                try {
                    Map<String, Object> sess = new HashMap<>();
                    sess.put("uid", req.getUid());
                    sess.put("purpose", "card_link");
                    sess.put("createdAt", java.time.Instant.now().toString());
                    if (StringUtils.hasText(email)) sess.put("email", email);
                    firestoreService.set("paystackSessions", reference, sess);
                } catch (Exception ignore) {}
                return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                        "provider", "paystack",
                        "authorizationUrl", data.path("authorization_url").asText(),
                        "accessCode", data.path("access_code").asText(),
                        "reference", reference
                ));
            }
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", true, "status", resp.statusCode()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Failed to initialize card link"));
        }
    }

    @GetMapping("/wallet/payment-methods")
    public ResponseEntity<?> walletList(@RequestParam("uid") String uid) {
        if (!StringUtils.hasText(uid)) return ResponseEntity.badRequest().body(Map.of("error", "uid is required"));
        try {
            var items = firestoreService.listSubcollection("clients", uid, "paymentMethods");
            return ResponseEntity.ok(Map.of("items", items));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Failed to list payment methods"));
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

    // Fallback for FE to persist card after returning from Paystack if webhook didn’t arrive yet
    @GetMapping("/session/{reference}")
    public ResponseEntity<?> getSessionAndPersist(@PathVariable("reference") String reference) {
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
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", true, "status", resp.statusCode()));
            }
            JsonNode root = objectMapper.readTree(resp.body());
            if (!root.path("status").asBoolean(false)) {
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

            // If this was a card link, upsert the authorization into client wallet now (same as webhook logic)
            JsonNode md = data.path("metadata");
            String purpose = md.path("purpose").asText("");
            String uid = md.path("uid").asText("");
            if (!StringUtils.hasText(uid)) {
                // Fallback: read our session mapping stored on initialize
                try {
                    Object sessSnap = firestoreService.get("paystackSessions", reference);
                    if (sessSnap != null) {
                        Class<?> docClass = Class.forName("com.google.cloud.firestore.DocumentSnapshot");
                        Boolean exists = (Boolean) docClass.getMethod("exists").invoke(sessSnap);
                        if (Boolean.TRUE.equals(exists)) {
                            @SuppressWarnings("unchecked") Map<String, Object> sess = (Map<String, Object>) docClass.getMethod("getData").invoke(sessSnap);
                            if (sess != null) {
                                Object suid = sess.get("uid");
                                if (suid != null) uid = String.valueOf(suid);
                                Object sp = sess.get("purpose");
                                if (StringUtils.hasText(purpose) == false && sp != null) purpose = String.valueOf(sp);
                            }
                        }
                    }
                } catch (Throwable ignore) {}
            }
            if ("card_link".equalsIgnoreCase(purpose) && StringUtils.hasText(uid)) {
                JsonNode auth = data.path("authorization");
                boolean reusable = auth.path("reusable").asBoolean(true);
                String authCode = auth.path("authorization_code").asText(null);
                if (reusable && StringUtils.hasText(authCode)) {
                    Map<String, Object> pm = new HashMap<>();
                    pm.put("id", authCode);
                    pm.put("authorization_code", authCode);
                    pm.put("reusable", true);
                    pm.put("card_type", auth.path("card_type").asText(null));
                    pm.put("brand", auth.path("brand").asText(null));
                    pm.put("last4", auth.path("last4").asText(null));
                    pm.put("exp_month", auth.path("exp_month").asText(null));
                    pm.put("exp_year", auth.path("exp_year").asText(null));
                    pm.put("bank", auth.path("bank").asText(null));
                    pm.put("country_code", auth.path("country_code").asText(null));
                    pm.put("channel", data.path("channel").asText("card"));
                    pm.put("email", data.path("customer").path("email").asText(null));
                    pm.put("createdAt", java.time.Instant.now().toString());
                    try { firestoreService.setInSubcollection("clients", uid, "paymentMethods", authCode, pm); } catch (Exception ignore) {}
                    out.put("savedPaymentMethod", Map.of(
                            "id", authCode,
                            "brand", pm.get("brand"),
                            "last4", pm.get("last4"),
                            "exp_month", pm.get("exp_month"),
                            "exp_year", pm.get("exp_year")
                    ));
                }
            }
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Failed to fetch session/verify"));
        }
    }

    @Data
    public static class EstimateRequest {
        private String serviceId; // optional: if provided, loads basePrice and addOns from services/{serviceId}
        private Long basePrice; // optional fallback if serviceId not provided; cents
        private String currency; // default ZAR
        private Double distanceKm; // optional distance for pricing
        private Long perKmCents; // optional per km rate in cents
        private List<String> addOnIds; // optional: ids matching services.addOns[].id
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

    @Data
    public static class CardLinkInitRequest {
        private String uid;
        private Long amount;
        private String currency;
        private String email;
        private String callbackUrl;
        private Boolean tokenizeOnly;
    }
}
