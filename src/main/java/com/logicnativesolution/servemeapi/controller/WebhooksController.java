package com.logicnativesolution.servemeapi.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.*;

@RestController
@RequestMapping("/webhooks/stripe")
@RequiredArgsConstructor
public class WebhooksController {

    private static final Logger log = LoggerFactory.getLogger(WebhooksController.class);

    private final FirestoreService firestoreService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostMapping
    public ResponseEntity<?> handle(@RequestBody String body,
                                    @RequestHeader(name = "Stripe-Signature", required = false) String sigHeader) {
        if (body == null || body.isBlank()) return ResponseEntity.badRequest().build();

        // Try to verify with Stripe SDK if present
        String secret = Optional.ofNullable(System.getenv("STRIPE_WEBHOOK_SECRET"))
                .orElse(Optional.ofNullable(System.getenv("STRIPE_WEBHOOK_KEY"))
                        .orElse(System.getProperty("stripe.webhookSecret")));
        Object event = null;
        boolean verified = false;
        if (StringUtils.hasText(secret) && StringUtils.hasText(sigHeader)) {
            try {
                Class<?> webhookClass = Class.forName("com.stripe.net.Webhook");
                Class<?> eventClass = Class.forName("com.stripe.model.Event");
                event = webhookClass.getMethod("constructEvent", String.class, String.class, String.class)
                        .invoke(null, body, sigHeader, secret);
                verified = true;
                return processStripeEvent(event);
            } catch (ClassNotFoundException e) {
                log.warn("Stripe SDK not available; falling back to JSON parsing without signature verification");
            } catch (Exception e) {
                log.warn("Stripe webhook signature verification failed: {}", e.getMessage());
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("invalid signature");
            }
        }

        // Fallback: parse JSON without verification (use only in test environments)
        try {
            JsonNode root = objectMapper.readTree(body);
            String type = root.path("type").asText(null);
            if (type == null) return ResponseEntity.badRequest().body("missing type");
            switch (type) {
                case "payment_intent.succeeded" -> onPaymentIntentUpdate(root, "succeeded");
                case "payment_intent.payment_failed" -> onPaymentIntentUpdate(root, "payment_failed");
                case "payment_intent.amount_capturable_updated" -> onPaymentIntentUpdate(root, "requires_capture");
                case "account.updated" -> onAccountUpdated(root);
                default -> log.info("Unhandled Stripe event type: {}", type);
            }
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("Failed to handle Stripe webhook", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    private ResponseEntity<?> processStripeEvent(Object event) {
        try {
            Class<?> eventClass = Class.forName("com.stripe.model.Event");
            String type = (String) eventClass.getMethod("getType").invoke(event);
            if (type == null) return ResponseEntity.ok().build();
            switch (type) {
                case "payment_intent.succeeded" -> onPaymentIntentUpdate(event, "succeeded");
                case "payment_intent.payment_failed" -> onPaymentIntentUpdate(event, "payment_failed");
                case "payment_intent.amount_capturable_updated" -> onPaymentIntentUpdate(event, "requires_capture");
                case "account.updated" -> onAccountUpdated(event);
                default -> log.info("Unhandled Stripe event type: {}", type);
            }
            return ResponseEntity.ok().build();
        } catch (ClassNotFoundException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("Stripe SDK not available");
        } catch (Exception e) {
            log.error("Error processing Stripe event", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    // ---- Handlers (overloads for SDK object event vs JSON node) ----

    private void onPaymentIntentUpdate(Object event, String status) throws Exception {
        // Extract paymentIntent id via SDK reflection
        Class<?> eventClass = Class.forName("com.stripe.model.Event");
        Object data = eventClass.getMethod("getDataObjectDeserializer").invoke(event);
        // data is EventDataObjectDeserializer
        Class<?> deserClass = Class.forName("com.stripe.net.ApiResource$ApiResourceProvidedObjectDeserializer");
        // Newer SDK uses com.stripe.net.EventDataObjectDeserializer, attempt both
        try {
            deserClass = Class.forName("com.stripe.net.EventDataObjectDeserializer");
        } catch (ClassNotFoundException ignore) {}
        Object obj = null;
        try {
            // Optional<Object> getObject()
            obj = deserClass.getMethod("getObject").invoke(data);
            if (obj instanceof Optional<?> opt && opt.isPresent()) obj = opt.get();
        } catch (NoSuchMethodException nsme) {
            // Fallback path: getRawJson()
            Object raw = deserClass.getMethod("getRawJson").invoke(data);
            if (raw instanceof String s) {
                JsonNode n = objectMapper.readTree(s);
                String piId = n.path("id").asText(null);
                if (piId != null) updateJobPaymentStatus(piId, status);
                return;
            }
        }
        if (obj != null) {
            try {
                Class<?> piClass = Class.forName("com.stripe.model.PaymentIntent");
                String id = (String) piClass.getMethod("getId").invoke(obj);
                if (id != null) updateJobPaymentStatus(id, status);
            } catch (ClassNotFoundException ignore) {
                // fallback impossible here
            }
        }
    }

    private void onPaymentIntentUpdate(JsonNode root, String status) {
        String id = root.path("data").path("object").path("id").asText(null);
        if (id != null) updateJobPaymentStatus(id, status);
    }

    private void onAccountUpdated(Object event) throws Exception {
        Class<?> eventClass = Class.forName("com.stripe.model.Event");
        Object data = eventClass.getMethod("getDataObjectDeserializer").invoke(event);
        Class<?> deserClass;
        try {
            deserClass = Class.forName("com.stripe.net.EventDataObjectDeserializer");
        } catch (ClassNotFoundException e) {
            deserClass = Class.forName("com.stripe.net.ApiResource$ApiResourceProvidedObjectDeserializer");
        }
        Object obj = null;
        try {
            obj = deserClass.getMethod("getObject").invoke(data);
            if (obj instanceof Optional<?> opt && opt.isPresent()) obj = opt.get();
        } catch (NoSuchMethodException nsme) {
            Object raw = deserClass.getMethod("getRawJson").invoke(data);
            if (raw instanceof String s) {
                JsonNode n = objectMapper.readTree(s);
                String accountId = n.path("id").asText(null);
                boolean payoutsEnabled = n.path("payouts_enabled").asBoolean(false);
                updateProviderPayouts(accountId, payoutsEnabled);
                return;
            }
        }
        if (obj != null) {
            try {
                Class<?> acctClass = Class.forName("com.stripe.model.Account");
                String accountId = (String) acctClass.getMethod("getId").invoke(obj);
                Boolean payoutsEnabled = (Boolean) acctClass.getMethod("getPayoutsEnabled").invoke(obj);
                updateProviderPayouts(accountId, Boolean.TRUE.equals(payoutsEnabled));
            } catch (ClassNotFoundException ignore) {}
        }
    }

    private void onAccountUpdated(JsonNode root) {
        String accountId = root.path("data").path("object").path("id").asText(null);
        boolean payoutsEnabled = root.path("data").path("object").path("payouts_enabled").asBoolean(false);
        updateProviderPayouts(accountId, payoutsEnabled);
    }

    // ---- Firestore updates (MVP scanning via listCollection) ----

    private void updateJobPaymentStatus(String paymentIntentId, String status) {
        if (paymentIntentId == null) return;
        try {
            List<Map<String, Object>> jobs = firestoreService.listCollection("jobs");
            for (Map<String, Object> j : jobs) {
                Object payment = j.get("payment");
                String id = null;
                if (payment instanceof Map<?,?> pm) {
                    Object pid = pm.get("paymentIntentId");
                    if (pid != null) id = String.valueOf(pid);
                }
                if (payment instanceof String) {
                    // legacy flat field
                    id = String.valueOf(payment);
                }
                if (paymentIntentId.equals(id)) {
                    String jobId = String.valueOf(j.get("id"));
                    Map<String, Object> update = new HashMap<>();
                    Map<String, Object> pay = new HashMap<>();
                    pay.put("paymentIntentId", paymentIntentId);
                    pay.put("status", status);
                    update.put("payment", pay);
                    update.put("updatedAt", java.time.Instant.now().toString());
                    firestoreService.set("jobs", jobId, update);
                    log.info("Updated job {} payment status -> {}", jobId, status);
                    break;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to update job by payment intent {}", paymentIntentId, e);
        }
    }

    private void updateProviderPayouts(String accountId, boolean payoutsEnabled) {
        if (accountId == null) return;
        try {
            List<Map<String, Object>> providers = firestoreService.listCollection("providers");
            for (Map<String, Object> p : providers) {
                String id = String.valueOf(p.get("id"));
                boolean match = false;
                Object stripe = p.get("stripe");
                if (stripe instanceof Map<?,?> sm) {
                    Object aid = sm.get("accountId");
                    if (aid != null && accountId.equals(String.valueOf(aid))) {
                        match = true;
                    }
                }
                if (match) {
                    Map<String, Object> stripeUpdate = new HashMap<>();
                    stripeUpdate.put("accountId", accountId);
                    stripeUpdate.put("payoutsEnabled", payoutsEnabled);
                    firestoreService.set("providers", id, Map.of("stripe", stripeUpdate, "updatedAt", java.time.Instant.now().toString()));
                    log.info("Updated provider {} payoutsEnabled -> {}", id, payoutsEnabled);
                    break;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to update provider payouts for account {}", accountId, e);
        }
    }
}
