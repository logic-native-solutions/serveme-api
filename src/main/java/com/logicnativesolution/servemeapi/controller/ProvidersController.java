package com.logicnativesolution.servemeapi.controller;

import com.logicnativesolution.servemeapi.dto.profile.CompleteProfileRequest;
import com.logicnativesolution.servemeapi.dto.profile.UpdateLocationRequest;
import com.logicnativesolution.servemeapi.service.FirestoreService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.core.env.Environment;

import java.security.Principal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/providers")
@RequiredArgsConstructor
public class ProvidersController {

    private final com.logicnativesolution.servemeapi.config.PaystackConfig paystackConfig;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    private static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371.0; // km
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat/2) * Math.sin(dLat/2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon/2) * Math.sin(dLon/2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
        return R * c;
    }

    private final FirestoreService firestoreService;
    private final Environment env;

    // In-memory cache for SA banks to avoid frequent upstream calls and survive temporary outages
    private volatile java.util.List<java.util.Map<String, Object>> saBanksCache;
    private volatile long saBanksCacheAtMs = 0L;
    private static final long SA_BANKS_TTL_MS = 6L * 60L * 60L * 1000L; // 6 hours

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(Principal principal) {
        String uid = principal != null ? principal.getName() : null;
        if (uid == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        Object snap = firestoreService.get("providers", uid);
        try {
            // Reflective DocumentSnapshot methods
            Class<?> docSnapClass = Class.forName("com.google.cloud.firestore.DocumentSnapshot");
            Boolean exists = (Boolean) docSnapClass.getMethod("exists").invoke(snap);
            if (!exists) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) docSnapClass.getMethod("getData").invoke(snap);
            if (data == null) data = new HashMap<>();
            data.put("id", uid);
            return ResponseEntity.ok(data);
        } catch (ClassNotFoundException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("Firebase SDK not available");
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Failed to read provider profile");
        }
    }

    @PostMapping("/onboarding")
    public ResponseEntity<?> completeOnboarding(@RequestBody CompleteProfileRequest req, Principal principal) {
        String uid = principal != null ? principal.getName() : null;
        if (uid == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();

        // Build provider service-related data only (no personal info here)
        Map<String, Object> provider = new HashMap<>();
        provider.put("userId", uid);
        if (req.getServiceTypes() != null) provider.put("serviceTypes", req.getServiceTypes());
        // Keep legacy serviceId if provided (for backward compatibility with older docs)
        if (req.getServiceId() != null) provider.put("serviceId", req.getServiceId());

        // NOTE: Stripe integration is deprecated and ignored.

        // Defaults for ProviderDoc
        provider.putIfAbsent("ratingAvg", 0.0d);
        provider.putIfAbsent("ratingCount", 0);
        Map<String, Object> verified = new HashMap<>();
        verified.put("identity", Boolean.FALSE);
        provider.putIfAbsent("verified", verified);
        provider.put("onboarded", true);
        provider.put("updatedAt", java.time.Instant.now().toString());
        provider.putIfAbsent("createdAt", java.time.Instant.now().toString());

        try {
            // Write provider doc
            firestoreService.set("providers", uid, provider);

            // If address is provided, update users/{uid}.defaultAddress in Firestore
            if (req.getAddressLine1() != null || req.getAddressLat() != null || req.getAddressLng() != null || req.getAddressGeohash() != null) {
                Map<String, Object> defaultAddress = new HashMap<>();
                if (req.getAddressLine1() != null) defaultAddress.put("line1", req.getAddressLine1());
                if (req.getAddressLat() != null) defaultAddress.put("lat", req.getAddressLat());
                if (req.getAddressLng() != null) defaultAddress.put("lng", req.getAddressLng());
                if (req.getAddressGeohash() != null) defaultAddress.put("geohash", req.getAddressGeohash());

                Map<String, Object> userUpdate = new HashMap<>();
                userUpdate.put("defaultAddress", defaultAddress);
                userUpdate.put("updatedAt", java.time.Instant.now().toString());
                firestoreService.set("users", uid, userUpdate);
            }

            // Auto-create Paystack subaccount during onboarding if details are provided
            boolean attemptedPaystack = false;
            if (paystackConfig.getSecretKey() != null) {
                String businessName = trimToNull(req.getBusinessName());
                String accountNumber = trimToNull(req.getAccountNumber());
                String settlementBank = trimToNull(req.getSettlementBank());
                String bankName = trimToNull(req.getBankName());
                if (accountNumber != null && (settlementBank != null || bankName != null)) {
                    attemptedPaystack = true;
                    try {
                        String resolvedBank = resolveSASettlementBank(settlementBank, bankName);
                        if (resolvedBank == null) {
                            return ResponseEntity.badRequest().body(Map.of(
                                    "error", true,
                                    "reason", "bank_not_resolved",
                                    "message", "Unable to resolve settlement bank for South Africa. Provide bankName (e.g., 'First National Bank', 'FNB', 'Standard Bank', 'ABSA')."
                            ));
                        }
                        Map<String, Object> body = new HashMap<>();
                        body.put("business_name", businessName != null ? businessName : ("Provider " + uid));
                        body.put("settlement_bank", resolvedBank);
                        body.put("account_number", accountNumber);
                        Double commission = paystackConfig.getCommissionPercent();
                        if (commission != null) body.put("percentage_charge", commission);
                        if (req.getSettlementSchedule() != null) body.put("settlement_schedule", req.getSettlementSchedule());
                        String json = objectMapper.writeValueAsString(body);
                        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                                .uri(java.net.URI.create(paystackConfig.getBaseUrl() + "/subaccount"))
                                .header("Authorization", paystackConfig.getAuthHeader())
                                .header("Content-Type", "application/json")
                                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json))
                                .build();
                        java.net.http.HttpResponse<String> resp = java.net.http.HttpClient.newHttpClient().send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
                        if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(resp.body());
                            if (root.path("status").asBoolean(false)) {
                                String subaccountCode = root.path("data").path("subaccount_code").asText(null);
                                Map<String, Object> paystack = new HashMap<>();
                                paystack.put("subaccountCode", subaccountCode);
                                paystack.put("settlementBank", resolvedBank);
                                paystack.put("accountNumber", accountNumber);
                                if (commission != null) paystack.put("percentageCharge", commission);
                                firestoreService.set("providers", uid, Map.of("paystack", paystack));
                            } else {
                                return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                                        "error", true,
                                        "stage", "create_subaccount",
                                        "message", root.path("message").asText("Paystack subaccount create failed")
                                ));
                            }
                        } else {
                            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                                    "error", true,
                                    "status", resp.statusCode(),
                                    "stage", "create_subaccount",
                                    "body", resp.body()
                            ));
                        }
                    } catch (Exception ex) {
                        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                                "error", true,
                                "stage", "create_subaccount",
                                "message", ex.getMessage()
                        ));
                    }
                }
            }

            Map<String, Object> out = new HashMap<>();
            out.put("onboarded", true);
            if (attemptedPaystack) {
                // include current paystack link status snapshot
                try {
                    Object snap = firestoreService.get("providers", uid);
                    Class<?> docSnapClass = Class.forName("com.google.cloud.firestore.DocumentSnapshot");
                    @SuppressWarnings("unchecked") Map<String, Object> data = (Map<String, Object>) docSnapClass.getMethod("getData").invoke(snap);
                    Object paystack = data != null ? data.get("paystack") : null;
                    if (paystack instanceof Map<?,?> m) {
                        out.put("paystack", m);
                    }
                } catch (Exception ignore) {}
            }
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Failed to complete onboarding");
        }
    }

    @PostMapping("/onboarding-link")
    public ResponseEntity<?> onboardingLink(Principal principal) {
        String uid = principal != null ? principal.getName() : null;
        if (uid == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        try {
            // If Stripe SDK is present but API key is missing, return a helpful 503 instead of 500
            try {
                Class<?> stripeClass = Class.forName("com.stripe.Stripe");
                String apiKey = null;
                try {
                    apiKey = (String) stripeClass.getMethod("getApiKey").invoke(null);
                } catch (NoSuchMethodException nsme) {
                    try {
                        apiKey = (String) stripeClass.getField("apiKey").get(null);
                    } catch (NoSuchFieldException ignored) { /* older/newer SDK variations */ }
                }
                if (apiKey == null || apiKey.isBlank()) {
                    String note = "Stripe SDK is present but secret key is not configured. Set STRIPE_SECRET_KEY or app.stripe.secretKey, then retry.";
                    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                            "error", true,
                            "reason", "stripe_not_configured",
                            "note", note
                    ));
                }
            } catch (ClassNotFoundException ignore) {
                // SDK not on classpath: handled later with mock fallback
            }

            // Read provider to check existing stripe.accountId
            String accountId = null;
            try {
                Object snap = firestoreService.get("providers", uid);
                Class<?> docSnapClass = Class.forName("com.google.cloud.firestore.DocumentSnapshot");
                Boolean exists = (Boolean) docSnapClass.getMethod("exists").invoke(snap);
                if (Boolean.TRUE.equals(exists)) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> data = (Map<String, Object>) docSnapClass.getMethod("getData").invoke(snap);
                    if (data != null && data.get("stripe") instanceof Map<?,?> m) {
                        Object aid = ((Map<?,?>) m).get("accountId");
                        if (aid != null) accountId = String.valueOf(aid);
                    }
                }
            } catch (ClassNotFoundException ignore) { /* handled below */ }

            // Basic validation: ignore mock or malformed stored ids
            if (accountId != null) {
                if (accountId.startsWith("mock_") || !accountId.startsWith("acct_")) {
                    accountId = null; // will create a fresh Express account below
                }
            }

            // If SDK is present and we have an id, verify that the account actually exists; if not, recreate
            try {
                if (accountId != null) {
                    Class<?> accountClass = Class.forName("com.stripe.model.Account");
                    try {
                        accountClass.getMethod("retrieve", String.class).invoke(null, accountId);
                    } catch (java.lang.reflect.InvocationTargetException ite) {
                        String msg = ite.getCause() != null ? ite.getCause().getMessage() : null;
                        if (msg != null && msg.toLowerCase().contains("no such account")) {
                            accountId = null; // will recreate
                        }
                    }
                }
            } catch (ClassNotFoundException ignore) { /* SDK not present; can't verify */ }

            // Create account if missing
            if (accountId == null) {
                try {
                    Class<?> accountCreateParams = Class.forName("com.stripe.param.AccountCreateParams");
                    Class<?> accountCreateParamsType = Class.forName("com.stripe.param.AccountCreateParams$Type");
                    Class<?> accountClass = Class.forName("com.stripe.model.Account");
                    Object builder = accountCreateParams.getMethod("builder").invoke(null);
                    Object express = Enum.valueOf((Class<Enum>) accountCreateParamsType, "EXPRESS");
                    builder.getClass().getMethod("setType", accountCreateParamsType).invoke(builder, express);
                    Object params = builder.getClass().getMethod("build").invoke(builder);
                    Object account = accountClass.getMethod("create", accountCreateParams).invoke(null, params);
                    accountId = (String) accountClass.getMethod("getId").invoke(account);
                    // persist on provider doc
                    Map<String, Object> stripe = new HashMap<>();
                    stripe.put("accountId", accountId);
                    firestoreService.set("providers", uid, Map.of("stripe", stripe));
                } catch (java.lang.reflect.InvocationTargetException ite) {
                    Throwable cause = ite.getCause();
                    String message = cause != null ? cause.getMessage() : "Stripe invocation failed";
                    return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                            "error", true,
                            "stage", "create_account",
                            "message", message
                    ));
                } catch (ClassNotFoundException e) {
                    // Graceful fallback when Stripe SDK is not present: create a mock accountId and persist it
                    accountId = "mock_" + uid;
                    Map<String, Object> stripe = new HashMap<>();
                    stripe.put("accountId", accountId);
                    stripe.put("payoutsEnabled", Boolean.FALSE);
                    firestoreService.set("providers", uid, Map.of("stripe", stripe));
                }
            }

            // Create onboarding account link with retry if stored accountId is invalid at Stripe
            try {
                Class<?> alParams = Class.forName("com.stripe.param.AccountLinkCreateParams");
                Class<?> alParamsType = Class.forName("com.stripe.param.AccountLinkCreateParams$Type");
                Class<?> alClass = Class.forName("com.stripe.model.AccountLink");

                // Resolve and validate onboarding URLs before building params
                String resolvedRefreshUrl = null;
                String resolvedReturnUrl = null;
                if (env != null) {
                    resolvedRefreshUrl = trimToNull(env.getProperty("app.stripe.refreshUrl"));
                    if (resolvedRefreshUrl == null) resolvedRefreshUrl = trimToNull(env.getProperty("stripe.refreshUrl"));
                    resolvedReturnUrl = trimToNull(env.getProperty("app.stripe.returnUrl"));
                    if (resolvedReturnUrl == null) resolvedReturnUrl = trimToNull(env.getProperty("stripe.returnUrl"));
                }
                if (resolvedRefreshUrl == null) resolvedRefreshUrl = trimToNull(System.getenv("STRIPE_ONBOARD_REFRESH_URL"));
                if (resolvedReturnUrl == null) resolvedReturnUrl = trimToNull(System.getenv("STRIPE_ONBOARD_RETURN_URL"));
                // Safe defaults for dev environments (Stripe requires absolute http(s) URLs)
                if (resolvedRefreshUrl == null) resolvedRefreshUrl = "https://serveme.example/connect/refresh";
                if (resolvedReturnUrl == null) resolvedReturnUrl = "https://serveme.example/connect/return";

                java.util.function.Predicate<String> isHttpUrl = (u) -> {
                    if (u == null) return false;
                    String x = u.toLowerCase();
                    return x.startsWith("http://") || x.startsWith("https://");
                };
                if (!isHttpUrl.test(resolvedRefreshUrl) || !isHttpUrl.test(resolvedReturnUrl)) {
                    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                            "error", true,
                            "reason", "config_invalid",
                            "message", "Stripe onboarding returnUrl/refreshUrl must be absolute http(s) URLs. Set app.stripe.returnUrl/app.stripe.refreshUrl or STRIPE_ONBOARD_RETURN_URL/STRIPE_ONBOARD_REFRESH_URL.",
                            "refreshUrl", resolvedRefreshUrl,
                            "returnUrl", resolvedReturnUrl
                    ));
                }

                String finalResolvedRefreshUrl = resolvedRefreshUrl;
                String finalResolvedReturnUrl = resolvedReturnUrl;

                java.util.function.Function<String, Object> buildParams = (String acct) -> {
                    try {
                        Object b = alParams.getMethod("builder").invoke(null);
                        b.getClass().getMethod("setAccount", String.class).invoke(b, acct);
                        Object t = Enum.valueOf((Class<Enum>) alParamsType, "ACCOUNT_ONBOARDING");
                        b.getClass().getMethod("setType", alParamsType).invoke(b, t);
                        b.getClass().getMethod("setRefreshUrl", String.class).invoke(b, finalResolvedRefreshUrl);
                        b.getClass().getMethod("setReturnUrl", String.class).invoke(b, finalResolvedReturnUrl);
                        return b.getClass().getMethod("build").invoke(b);
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                };

                boolean retried = false;
                while (true) {
                    try {
                        Object params = buildParams.apply(accountId);
                        Object link = alClass.getMethod("create", alParams).invoke(null, params);
                        String url = (String) alClass.getMethod("getUrl").invoke(link);
                        return ResponseEntity.ok(Map.of("url", url, "accountId", accountId));
                    } catch (java.lang.reflect.InvocationTargetException ite) {
                        String msg = ite.getCause() != null ? ite.getCause().getMessage() : null;
                        boolean noSuchAccount = msg != null && msg.toLowerCase().contains("no such account");
                        if (!retried && noSuchAccount) {
                            // Recreate the account once, persist, and retry link creation
                            try {
                                Class<?> accountCreateParams = Class.forName("com.stripe.param.AccountCreateParams");
                                Class<?> accountCreateParamsType = Class.forName("com.stripe.param.AccountCreateParams$Type");
                                Class<?> accountClass = Class.forName("com.stripe.model.Account");
                                Object builder2 = accountCreateParams.getMethod("builder").invoke(null);
                                Object express2 = Enum.valueOf((Class<Enum>) accountCreateParamsType, "EXPRESS");
                                builder2.getClass().getMethod("setType", accountCreateParamsType).invoke(builder2, express2);
                                Object params2 = builder2.getClass().getMethod("build").invoke(builder2);
                                Object account2 = accountClass.getMethod("create", accountCreateParams).invoke(null, params2);
                                accountId = (String) accountClass.getMethod("getId").invoke(account2);
                                Map<String, Object> stripe = new HashMap<>();
                                stripe.put("accountId", accountId);
                                firestoreService.set("providers", uid, Map.of("stripe", stripe));
                                retried = true;
                                continue; // retry loop
                            } catch (Exception recreateEx) {
                                String m = recreateEx.getMessage();
                                return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                                        "error", true,
                                        "stage", "recreate_account_after_no_such_account",
                                        "message", m
                                ));
                            }
                        }
                        String message = msg != null ? msg : "Stripe invocation failed";
                        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                                "error", true,
                                "stage", "create_onboarding_link",
                                "message", message
                        ));
                    }
                }
            } catch (ClassNotFoundException e) {
                // Graceful fallback when Stripe SDK is not present: return a mock onboarding link and guidance
                String url = "serveme://connect/return?mock=1";
                String note = "Stripe SDK not available on backend. To generate a real Stripe onboarding link, add Stripe Java SDK to the runtime, set STRIPE_SECRET_KEY, and configure app.stripe.returnUrl/app.stripe.refreshUrl (or STRIPE_ONBOARD_RETURN_URL/STRIPE_ONBOARD_REFRESH_URL). In Stripe Dashboard (Test mode), you can also create onboarding links under Connect > Accounts.";
                return ResponseEntity.ok(Map.of("url", url, "accountId", accountId, "mock", true, "note", note));
            }
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Failed to create onboarding link");
        }
    }

    @GetMapping("/stripe-status")
    public ResponseEntity<?> stripeStatus(Principal principal) {
        String uid = principal != null ? principal.getName() : null;
        if (uid == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        try {
            String accountId = null;
            try {
                Object snap = firestoreService.get("providers", uid);
                Class<?> docSnapClass = Class.forName("com.google.cloud.firestore.DocumentSnapshot");
                Boolean exists = (Boolean) docSnapClass.getMethod("exists").invoke(snap);
                if (Boolean.TRUE.equals(exists)) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> data = (Map<String, Object>) docSnapClass.getMethod("getData").invoke(snap);
                    if (data != null && data.get("stripe") instanceof Map<?,?> m) {
                        Object aid = ((Map<?,?>) m).get("accountId");
                        if (aid != null) accountId = String.valueOf(aid);
                    }
                }
            } catch (ClassNotFoundException ignore) { /* handled below */ }
            if (accountId == null) {
                return ResponseEntity.ok(Map.of(
                        "linked", false,
                        "payoutsEnabled", false
                ));
            }
            Boolean payoutsEnabled = null;
            try {
                Class<?> accountClass = Class.forName("com.stripe.model.Account");
                Object account = accountClass.getMethod("retrieve", String.class).invoke(null, accountId);
                payoutsEnabled = (Boolean) accountClass.getMethod("getPayoutsEnabled").invoke(account);
            } catch (ClassNotFoundException e) {
                // Stripe SDK missing; return last known snapshot only
            }
            // Persist snapshot if we got a value
            if (payoutsEnabled != null) {
                Map<String, Object> stripe = new HashMap<>();
                stripe.put("accountId", accountId);
                stripe.put("payoutsEnabled", payoutsEnabled);
                firestoreService.set("providers", uid, Map.of("stripe", stripe));
            }
            // Read latest
            Object snap = firestoreService.get("providers", uid);
            Class<?> docSnapClass = Class.forName("com.google.cloud.firestore.DocumentSnapshot");
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) docSnapClass.getMethod("getData").invoke(snap);
            Object payouts = null;
            if (data != null && data.get("stripe") instanceof Map<?,?> m) payouts = ((Map<?,?>) m).get("payoutsEnabled");
            boolean payoutsVal = payouts instanceof Boolean b ? b : false;
            return ResponseEntity.ok(Map.of(
                    "linked", true,
                    "accountId", accountId,
                    "payoutsEnabled", payoutsVal
            ));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Failed to read Stripe status");
        }
    }

    @GetMapping("/stripe-account")
    public ResponseEntity<?> stripeAccount(Principal principal) {
        String uid = principal != null ? principal.getName() : null;
        if (uid == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        try {
            // Resolve provider's Stripe accountId from Firestore
            String accountId = null;
            try {
                Object snap = firestoreService.get("providers", uid);
                Class<?> docSnapClass = Class.forName("com.google.cloud.firestore.DocumentSnapshot");
                Boolean exists = (Boolean) docSnapClass.getMethod("exists").invoke(snap);
                if (Boolean.TRUE.equals(exists)) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> data = (Map<String, Object>) docSnapClass.getMethod("getData").invoke(snap);
                    if (data != null && data.get("stripe") instanceof Map<?,?> m) {
                        Object aid = ((Map<?,?>) m).get("accountId");
                        if (aid != null) accountId = String.valueOf(aid);
                    }
                }
            } catch (ClassNotFoundException ignore) { /* handled below */ }

            if (accountId == null || accountId.startsWith("mock_")) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                        "error", true,
                        "reason", "not_linked",
                        "message", "Provider is not linked to a real Stripe account. Run onboarding to create/link an account."
                ));
            }

            // Ensure Stripe SDK is present and API key configured
            try {
                Class<?> stripeClass = Class.forName("com.stripe.Stripe");
                String apiKey = null;
                try {
                    apiKey = (String) stripeClass.getMethod("getApiKey").invoke(null);
                } catch (NoSuchMethodException nsme) {
                    try { apiKey = (String) stripeClass.getField("apiKey").get(null); } catch (NoSuchFieldException ignored) {}
                }
                if (apiKey == null || apiKey.isBlank()) {
                    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                            "error", true,
                            "reason", "stripe_not_configured",
                            "message", "Stripe SDK present but secret key is not configured. Set STRIPE_SECRET_KEY or app.stripe.secretKey."
                    ));
                }
            } catch (ClassNotFoundException e) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                        "error", true,
                        "reason", "stripe_sdk_missing",
                        "message", "Stripe SDK not available on backend. Install com.stripe:stripe-java and configure STRIPE_SECRET_KEY."
                ));
            }

            // Build RequestOptions with connected account header
            Object requestOptions;
            try {
                Class<?> requestOptionsClass = Class.forName("com.stripe.net.RequestOptions");
                Object roBuilder = requestOptionsClass.getMethod("builder").invoke(null);
                roBuilder.getClass().getMethod("setStripeAccount", String.class).invoke(roBuilder, accountId);
                requestOptions = roBuilder.getClass().getMethod("build").invoke(roBuilder);
            } catch (Exception ex) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                        "error", true,
                        "stage", "build_request_options",
                        "message", ex.getMessage()
                ));
            }

            // Retrieve Account (payoutsEnabled)
            Boolean payoutsEnabled = null;
            try {
                Class<?> accountClass = Class.forName("com.stripe.model.Account");
                Object account = accountClass.getMethod("retrieve", String.class).invoke(null, accountId);
                payoutsEnabled = (Boolean) accountClass.getMethod("getPayoutsEnabled").invoke(account);
            } catch (java.lang.reflect.InvocationTargetException ite) {
                String msg = ite.getCause() != null ? ite.getCause().getMessage() : "Stripe invocation failed";
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                        "error", true,
                        "stage", "retrieve_account",
                        "message", msg
                ));
            }

            // Retrieve Balance for connected account
            Map<String, Object> balancesOut = new HashMap<>();
            try {
                Class<?> balanceClass = Class.forName("com.stripe.model.Balance");
                Object balance;
                try {
                    // Try v22 signature: retrieve(params, requestOptions)
                    Class<?> balParams = Class.forName("com.stripe.param.BalanceRetrieveParams");
                    Object params = balParams.getMethod("builder").invoke(null);
                    params = params.getClass().getMethod("build").invoke(params);
                    balance = balanceClass.getMethod("retrieve", balParams, Class.forName("com.stripe.net.RequestOptions")).invoke(null, params, requestOptions);
                } catch (ClassNotFoundException | NoSuchMethodException sigFallback) {
                    // Fallback older signature: retrieve(requestOptions)
                    balance = balanceClass.getMethod("retrieve", Class.forName("com.stripe.net.RequestOptions")).invoke(null, requestOptions);
                }
                // Extract available and pending lists
                java.util.function.Function<Object, java.util.List<Map<String, Object>>> extractMoneyList = (obj) -> {
                    try {
                        java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();
                        java.util.List<?> list = (java.util.List<?>) obj;
                        if (list == null) return java.util.List.of();
                        for (Object money : list) {
                            Long amount = (Long) money.getClass().getMethod("getAmount").invoke(money);
                            String currency = (String) money.getClass().getMethod("getCurrency").invoke(money);
                            out.add(Map.of("amount", amount, "currency", currency));
                        }
                        return out;
                    } catch (Exception e) { return java.util.List.of(); }
                };
                Object available = balanceClass.getMethod("getAvailable").invoke(balance);
                Object pending = balanceClass.getMethod("getPending").invoke(balance);
                balancesOut.put("available", extractMoneyList.apply(available));
                balancesOut.put("pending", extractMoneyList.apply(pending));
            } catch (java.lang.reflect.InvocationTargetException ite) {
                String msg = ite.getCause() != null ? ite.getCause().getMessage() : "Stripe invocation failed";
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                        "error", true,
                        "stage", "retrieve_balance",
                        "message", msg
                ));
            } catch (ClassNotFoundException e) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                        "error", true,
                        "reason", "stripe_sdk_missing",
                        "message", "Stripe SDK classes not found when retrieving balance."
                ));
            }

            // List recent balance transactions (history)
            java.util.List<Map<String, Object>> txOut = new java.util.ArrayList<>();
            try {
                Class<?> txClass = Class.forName("com.stripe.model.BalanceTransaction");
                Class<?> txParams = Class.forName("com.stripe.param.BalanceTransactionListParams");
                Class<?> txCollection = Class.forName("com.stripe.model.BalanceTransactionCollection");
                Object pBuilder = txParams.getMethod("builder").invoke(null);
                // Stripe Java SDK expects java.lang.Long for setLimit; using primitive long via reflection can fail.
                pBuilder.getClass().getMethod("setLimit", Long.class).invoke(pBuilder, Long.valueOf(20));
                Object params = pBuilder.getClass().getMethod("build").invoke(pBuilder);
                Object collection = txClass.getMethod("list", txParams, Class.forName("com.stripe.net.RequestOptions")).invoke(null, params, requestOptions);
                @SuppressWarnings("unchecked")
                java.util.List<Object> data = (java.util.List<Object>) txCollection.getMethod("getData").invoke(collection);
                if (data != null) {
                    for (Object tx : data) {
                        Map<String, Object> one = new HashMap<>();
                        try { one.put("id", txClass.getMethod("getId").invoke(tx)); } catch (Exception ignore) {}
                        try { one.put("amount", txClass.getMethod("getAmount").invoke(tx)); } catch (Exception ignore) {}
                        try { one.put("currency", txClass.getMethod("getCurrency").invoke(tx)); } catch (Exception ignore) {}
                        try { one.put("type", txClass.getMethod("getType").invoke(tx)); } catch (Exception ignore) {}
                        try { one.put("reportingCategory", txClass.getMethod("getReportingCategory").invoke(tx)); } catch (Exception ignore) {}
                        try { one.put("description", txClass.getMethod("getDescription").invoke(tx)); } catch (Exception ignore) {}
                        try { one.put("created", txClass.getMethod("getCreated").invoke(tx)); } catch (Exception ignore) {}
                        try { one.put("fee", txClass.getMethod("getFee").invoke(tx)); } catch (Exception ignore) {}
                        try { one.put("net", txClass.getMethod("getNet").invoke(tx)); } catch (Exception ignore) {}
                        txOut.add(one);
                    }
                }
            } catch (java.lang.reflect.InvocationTargetException ite) {
                String msg = ite.getCause() != null ? ite.getCause().getMessage() : "Stripe invocation failed";
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                        "error", true,
                        "stage", "list_balance_transactions",
                        "message", msg
                ));
            } catch (ClassNotFoundException e) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                        "error", true,
                        "reason", "stripe_sdk_missing",
                        "message", "Stripe SDK classes not found when listing transactions."
                ));
            }

            Map<String, Object> out = new HashMap<>();
            out.put("accountId", accountId);
            out.put("payoutsEnabled", payoutsEnabled != null ? payoutsEnabled : Boolean.FALSE);
            out.put("balances", balancesOut);
            out.put("transactions", txOut);
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Failed to fetch Stripe account info");
        }
    }

    @GetMapping("/nearby")
    public ResponseEntity<?> nearby(
            @RequestParam double lat,
            @RequestParam double lng,
            @RequestParam String serviceType,
            @RequestParam(required = false, defaultValue = "10") double radiusKm,
            Principal principal
    ) {
        String uid = principal != null ? principal.getName() : null;
        if (uid == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        try {
            @SuppressWarnings("unchecked")
            java.util.List<java.util.Map<String, Object>> raw = firestoreService.listCollection("providers");
            java.util.List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();
            for (java.util.Map<String, Object> m : raw) {
                if (m == null) continue;
                // must be online and support the requested serviceType
                Object online = m.get("isOnline");
                if (!(online instanceof Boolean bo && Boolean.TRUE.equals(bo))) continue;
                Object sts = m.get("serviceTypes");
                boolean supports = false;
                if (sts instanceof java.util.List<?> list) {
                    for (Object o : list) { if (serviceType.equals(String.valueOf(o))) { supports = true; break; } }
                }
                if (!supports) continue;
                // distance filter
                Double plat = toDouble(m.get("lat"));
                Double plng = toDouble(m.get("lng"));
                if (plat == null || plng == null) continue;
                double d = haversineKm(lat, lng, plat, plng);
                if (d <= radiusKm) {
                    java.util.HashMap<String, Object> brief = new java.util.HashMap<>();
                    brief.put("id", m.get("id"));
                    brief.put("userId", m.get("userId"));
                    brief.put("lat", plat);
                    brief.put("lng", plng);
                    brief.put("distanceKm", Math.round(d * 10.0) / 10.0);
                    brief.put("ratingAvg", m.getOrDefault("ratingAvg", 0.0));
                    brief.put("ratingCount", m.getOrDefault("ratingCount", 0));
                    out.add(brief);
                }
            }
            // sort by distance asc
            out.sort(java.util.Comparator.comparingDouble(o -> ((Number) o.get("distanceKm")).doubleValue()));
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Failed to query nearby providers");
        }
    }

    private static Double toDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) {
            try { return Double.parseDouble(s); } catch (Exception ignore) { return null; }
        }
        return null;
    }

    @PostMapping("/location")
    public ResponseEntity<?> updateLocation(@RequestBody UpdateLocationRequest req, Principal principal) {
        String uid = principal != null ? principal.getName() : null;
        if (uid == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        Map<String, Object> update = new HashMap<>();
        update.put("lat", req.getLat());
        update.put("lng", req.getLng());
        update.put("isOnline", req.isOnline());
        // Compute and store geohash (precision 7 by default)
        try {
            String geohash = com.logicnativesolution.servemeapi.util.GeohashUtil.encode(req.getLat(), req.getLng(), 7);
            update.put("geohash", geohash);
        } catch (Exception ignore) {}
        try {
            firestoreService.set("providers", uid, update);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Failed to update location");
        }
    }

    // --- Earning Goal Endpoints ---
    @GetMapping("/earning-goal")
    public ResponseEntity<?> getEarningGoal(Principal principal) {
        String uid = principal != null ? principal.getName() : null;
        if (uid == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        try {
            Object snap = firestoreService.getFromSubcollection("providers", uid, "settings", "earningGoal");
            Class<?> docSnapClass = Class.forName("com.google.cloud.firestore.DocumentSnapshot");
            Boolean exists = (Boolean) docSnapClass.getMethod("exists").invoke(snap);
            if (!Boolean.TRUE.equals(exists)) {
                return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) docSnapClass.getMethod("getData").invoke(snap);
            if (data == null) return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
            Map<String, Object> out = new HashMap<>();
            if (data.get("amount") != null) out.put("amount", ((Number) data.get("amount")).intValue());
            if (data.get("currency") != null) out.put("currency", String.valueOf(data.get("currency")));
            if (data.get("period") != null) out.put("period", String.valueOf(data.get("period")));
            if (data.get("startDate") != null) out.put("startDate", String.valueOf(data.get("startDate")));
            return ResponseEntity.ok(out);
        } catch (ClassNotFoundException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "message", "Firestore SDK not available",
                    "stage", "persistence"
            ));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "message", "Failed to read earning goal",
                    "stage", "persistence"
            ));
        }
    }

    @PutMapping("/earning-goal")
    public ResponseEntity<?> putEarningGoal(@RequestBody com.logicnativesolution.servemeapi.dto.profile.EarningGoalDto req, Principal principal) {
        String uid = principal != null ? principal.getName() : null;
        if (uid == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();

        // Validate input
        if (req.getAmount() == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Validation failed: amount is required",
                    "field", "amount",
                    "code", "invalid_amount",
                    "stage", "validation"
            ));
        }
        if (req.getAmount() < 0) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Validation failed: amount must be >= 0",
                    "field", "amount",
                    "code", "invalid_amount",
                    "stage", "validation"
            ));
        }
        String currency = req.getCurrency() != null ? req.getCurrency().toLowerCase() : null;
        if (currency == null || !currency.matches("[a-z]{3,5}")) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Validation failed: currency must be 3-5 lowercase letters",
                    "field", "currency",
                    "code", "invalid_currency",
                    "stage", "validation"
            ));
        }
        String period = req.getPeriod() != null ? req.getPeriod().toLowerCase() : null;
        if (period == null || !(period.equals("week") || period.equals("month"))) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Validation failed: period must be 'week' or 'month'",
                    "field", "period",
                    "code", "invalid_period",
                    "stage", "validation"
            ));
        }
        java.time.LocalDate startDate = null;
        if (req.getStartDate() != null && !req.getStartDate().isBlank()) {
            try {
                startDate = java.time.LocalDate.parse(req.getStartDate());
            } catch (Exception ex) {
                return ResponseEntity.badRequest().body(Map.of(
                        "message", "Validation failed: startDate must be YYYY-MM-DD",
                        "field", "startDate",
                        "code", "invalid_startDate",
                        "stage", "validation"
                ));
            }
            java.time.LocalDate now = java.time.LocalDate.now();
            if (startDate.isBefore(now.minusYears(2)) || startDate.isAfter(now.plusYears(2))) {
                return ResponseEntity.badRequest().body(Map.of(
                        "message", "Validation failed: startDate out of range (±2 years)",
                        "field", "startDate",
                        "code", "invalid_startDate",
                        "stage", "validation"
                ));
            }
        }
        if (startDate == null) {
            java.time.LocalDate today = java.time.LocalDate.now();
            if ("week".equals(period)) {
                java.time.DayOfWeek first = java.time.DayOfWeek.MONDAY; // ISO week starts Monday
                int diff = today.getDayOfWeek().getValue() - first.getValue();
                startDate = today.minusDays(diff);
            } else { // month
                startDate = today.withDayOfMonth(1);
            }
        }

        try {
            // Determine if creating or updating
            boolean creating;
            try {
                Object snap = firestoreService.getFromSubcollection("providers", uid, "settings", "earningGoal");
                Class<?> docSnapClass = Class.forName("com.google.cloud.firestore.DocumentSnapshot");
                Boolean exists = (Boolean) docSnapClass.getMethod("exists").invoke(snap);
                creating = !Boolean.TRUE.equals(exists);
            } catch (Exception ignore) { creating = true; }

            Map<String, Object> toSave = new HashMap<>();
            toSave.put("amount", req.getAmount());
            toSave.put("currency", currency);
            toSave.put("period", period);
            toSave.put("startDate", startDate.toString());
            toSave.put("updatedAt", java.time.Instant.now().toString());
            toSave.put("updatedBy", uid);

            firestoreService.setInSubcollection("providers", uid, "settings", "earningGoal", toSave);

            Map<String, Object> out = new HashMap<>();
            out.put("amount", req.getAmount());
            out.put("currency", currency);
            out.put("period", period);
            out.put("startDate", startDate.toString());
            if (creating) {
                return ResponseEntity.status(HttpStatus.CREATED).body(out);
            } else {
                return ResponseEntity.ok(out);
            }
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "message", "Failed to save earning goal",
                    "stage", "persistence"
            ));
        }
    }

    @GetMapping("/paystack-status")
    public ResponseEntity<?> paystackStatus(Principal principal) {
        String uid = principal != null ? principal.getName() : null;
        if (uid == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        try {
            Object snap = firestoreService.get("providers", uid);
            Class<?> docSnapClass = Class.forName("com.google.cloud.firestore.DocumentSnapshot");
            Boolean exists = (Boolean) docSnapClass.getMethod("exists").invoke(snap);
            String subCode = null;
            if (Boolean.TRUE.equals(exists)) {
                @SuppressWarnings("unchecked") Map<String, Object> data = (Map<String, Object>) docSnapClass.getMethod("getData").invoke(snap);
                if (data != null && data.get("paystack") instanceof Map<?,?> m) {
                    Object sc = ((Map<?,?>) m).get("subaccountCode");
                    if (sc != null) subCode = String.valueOf(sc);
                }
            }
            boolean linked = subCode != null && !subCode.isBlank();
            java.util.Map<String, Object> out = new java.util.HashMap<>();
            out.put("linked", linked);
            out.put("subaccountCode", subCode); // may be null when not linked
            return ResponseEntity.ok(out);
        } catch (ClassNotFoundException e) {
            // If Firestore SDK isn't available at runtime, do not confuse users; treat as not linked.
            java.util.Map<String, Object> out = new java.util.HashMap<>();
            out.put("linked", false);
            out.put("subaccountCode", null);
            out.put("source", "fallback");
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            // Any read error should not block onboarding UX; return not linked.
            java.util.Map<String, Object> out = new java.util.HashMap<>();
            out.put("linked", false);
            out.put("subaccountCode", null);
            out.put("source", "error");
            return ResponseEntity.ok(out);
        }
    }

    @PostMapping("/paystack/subaccount")
    public ResponseEntity<?> upsertSubaccount(@RequestBody PaystackSubaccountRequest req, Principal principal) {
        String uid = principal != null ? principal.getName() : null;
        if (uid == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        if (paystackConfig.getSecretKey() == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "error", "Paystack not configured",
                    "message", "Set PAYSTACK_SECRET_KEY or app.paystack.secretKey"
            ));
        }
        try {
            // Read existing subaccount code
            Object snap = firestoreService.get("providers", uid);
            Class<?> docSnapClass = Class.forName("com.google.cloud.firestore.DocumentSnapshot");
            String existingCode = null;
            try {
                Boolean exists = (Boolean) docSnapClass.getMethod("exists").invoke(snap);
                if (Boolean.TRUE.equals(exists)) {
                    @SuppressWarnings("unchecked") Map<String, Object> data = (Map<String, Object>) docSnapClass.getMethod("getData").invoke(snap);
                    if (data != null && data.get("paystack") instanceof Map<?,?> m) {
                        Object sc = ((Map<?,?>) m).get("subaccountCode");
                        if (sc != null) existingCode = String.valueOf(sc);
                    }
                }
            } catch (Exception ignore) {}

            // Auto-resolve settlement bank for South Africa if not provided explicitly
            String resolvedSettlementBank = resolveSASettlementBank(req.getSettlementBank(), req.getBankName());
            if (resolvedSettlementBank == null) {
                return ResponseEntity.badRequest().body(java.util.Map.of(
                        "error", true,
                        "reason", "bank_not_resolved",
                        "message", "Unable to resolve settlement bank for South Africa. Provide bankName (e.g., 'First National Bank', 'FNB', 'Standard Bank', 'ABSA')."
                ));
            }

            java.util.Map<String, Object> body = new java.util.HashMap<>();
            body.put("business_name", req.getBusinessName());
            body.put("settlement_bank", resolvedSettlementBank);
            body.put("account_number", req.getAccountNumber());
            // Enforce owner-controlled commission percentage; ignore any client-provided value.
            Double commission = paystackConfig.getCommissionPercent();
            if (commission != null) body.put("percentage_charge", commission);
            if (req.getSettlementSchedule() != null) body.put("settlement_schedule", req.getSettlementSchedule());

            String json = objectMapper.writeValueAsString(body);
            java.net.http.HttpRequest.Builder rb = java.net.http.HttpRequest.newBuilder()
                    .header("Authorization", paystackConfig.getAuthHeader())
                    .header("Content-Type", "application/json");
            java.net.http.HttpRequest request;
            if (existingCode == null || existingCode.isBlank()) {
                request = rb.uri(java.net.URI.create(paystackConfig.getBaseUrl() + "/subaccount"))
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json))
                        .build();
            } else {
                request = rb.uri(java.net.URI.create(paystackConfig.getBaseUrl() + "/subaccount/" + existingCode))
                        .PUT(java.net.http.HttpRequest.BodyPublishers.ofString(json))
                        .build();
            }
            java.net.http.HttpResponse<String> resp = java.net.http.HttpClient.newHttpClient().send(request, java.net.http.HttpResponse.BodyHandlers.ofString());

            // Helper to persist and return success
            java.util.function.Function<com.fasterxml.jackson.databind.JsonNode, ResponseEntity<?>> onSuccess = (root) -> {
                com.fasterxml.jackson.databind.JsonNode data = root.path("data");
                String subaccountCode = data.path("subaccount_code").asText(null);
                java.util.Map<String, Object> paystack = new java.util.HashMap<>();
                paystack.put("subaccountCode", subaccountCode);
                paystack.put("settlementBank", resolvedSettlementBank);
                paystack.put("accountNumber", req.getAccountNumber());
                if (commission != null) paystack.put("percentageCharge", commission);
                try {
                    firestoreService.set("providers", uid, java.util.Map.of("paystack", paystack));
                } catch (Exception ex) {
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(java.util.Map.of(
                            "error", true,
                            "reason", "persist_failed",
                            "message", "Subaccount created but failed to persist mapping",
                            "details", ex.getMessage()
                    ));
                }
                return ResponseEntity.ok(java.util.Map.of(
                        "linked", true,
                        "subaccountCode", subaccountCode
                ));
            };

            // Parse primary response
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(resp.body());
                boolean status = root.path("status").asBoolean(false);
                if (status) {
                    return onSuccess.apply(root);
                }
                // If update attempt failed and we have an existing code, try creating a fresh subaccount
                String message = root.path("message").asText("");
                boolean notFound = existingCode != null && !existingCode.isBlank() && message.toLowerCase().contains("not found");
                if (notFound) {
                    existingCode = null; // force create below
                } else {
                    // For other 2xx but status=false responses, fall through to conditional create when we were updating
                }
            } else if (resp.statusCode() == 404 && existingCode != null && !existingCode.isBlank()) {
                // Treat 404 on update as not found and force create
                existingCode = null;
            } else if (existingCode == null || existingCode.isBlank()) {
                // Non-2xx on create attempt → return error with payload
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(java.util.Map.of(
                        "error", true,
                        "status", resp.statusCode(),
                        "stage", "create_subaccount",
                        "body", resp.body()
                ));
            } else {
                // Non-2xx on update; attempt create
                existingCode = null;
            }

            // If we reach here and existingCode is null → attempt to CREATE a new subaccount as fallback
            if (existingCode == null || existingCode.isBlank()) {
                java.net.http.HttpRequest createReq = java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(paystackConfig.getBaseUrl() + "/subaccount"))
                        .header("Authorization", paystackConfig.getAuthHeader())
                        .header("Content-Type", "application/json")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json))
                        .build();
                java.net.http.HttpResponse<String> createResp = java.net.http.HttpClient.newHttpClient().send(createReq, java.net.http.HttpResponse.BodyHandlers.ofString());
                if (createResp.statusCode() >= 200 && createResp.statusCode() < 300) {
                    com.fasterxml.jackson.databind.JsonNode root2 = objectMapper.readTree(createResp.body());
                    if (root2.path("status").asBoolean(false)) {
                        return onSuccess.apply(root2);
                    }
                    return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(java.util.Map.of(
                            "error", true,
                            "stage", "create_subaccount",
                            "message", root2.path("message").asText("Paystack subaccount create failed")
                    ));
                }
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(java.util.Map.of(
                        "error", true,
                        "status", createResp.statusCode(),
                        "stage", "create_subaccount",
                        "body", createResp.body()
                ));
            }

            // If we were updating and both update & fallback create were not taken, return the original response
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(java.util.Map.of(
                    "error", true,
                    "status", resp.statusCode(),
                    "stage", (existingCode == null || existingCode.isBlank()) ? "create_subaccount" : "update_subaccount",
                    "body", resp.body()
            ));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(java.util.Map.of(
                    "error", true,
                    "reason", "exception",
                    "message", "Failed to upsert Paystack subaccount",
                    "details", e.getMessage()
            ));
        }
    }

    @GetMapping("/paystack/banks")
    public ResponseEntity<?> listSABanks(Principal principal) {
        String uid = principal != null ? principal.getName() : null;
        if (uid == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        if (paystackConfig.getSecretKey() == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "error", "Paystack not configured",
                    "message", "Set PAYSTACK_SECRET_KEY or app.paystack.secretKey"
            ));
        }
        try {
            java.util.List<java.util.Map<String, Object>> banks = fetchSABanks();
            java.util.Map<String, Object> out = new java.util.HashMap<>();
            out.put("country", "ZA");
            out.put("currency", "ZAR");
            out.put("banks", banks);
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            // Be resilient: return cached or built-in fallback list with 200 OK so FE can still render options
            java.util.List<java.util.Map<String, Object>> banks = (saBanksCache != null && !saBanksCache.isEmpty()) ? saBanksCache : defaultSABanks();
            java.util.Map<String, Object> out = new java.util.HashMap<>();
            out.put("country", "ZA");
            out.put("currency", "ZAR");
            out.put("banks", banks);
            out.put("stale", true);
            out.put("source", (saBanksCache != null && !saBanksCache.isEmpty()) ? "cache" : "fallback");
            return ResponseEntity.ok(out);
        }
    }

    private java.util.List<java.util.Map<String, Object>> fetchSABanks() throws Exception {
        long now = System.currentTimeMillis();
        // Serve from fresh cache if available
        if (saBanksCache != null && (now - saBanksCacheAtMs) < SA_BANKS_TTL_MS) {
            return saBanksCache;
        }
        String url1 = paystackConfig.getBaseUrl() + "/bank?country=ZA";
        String url2 = paystackConfig.getBaseUrl() + "/bank?currency=ZAR";
        java.util.List<java.util.Map<String, Object>> out = tryFetchBanks(url1);
        if (out == null || out.isEmpty()) out = tryFetchBanks(url2);
        if (out != null && !out.isEmpty()) {
            saBanksCache = out;
            saBanksCacheAtMs = now;
            return out;
        }
        // If upstream failed/empty, return stale cache if any
        if (saBanksCache != null && !saBanksCache.isEmpty()) return saBanksCache;
        return java.util.List.of();
    }

    private java.util.List<java.util.Map<String, Object>> tryFetchBanks(String url) throws Exception {
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .header("Authorization", paystackConfig.getAuthHeader())
                .GET()
                .build();
        java.net.http.HttpResponse<String> resp = java.net.http.HttpClient.newHttpClient().send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(resp.body());
            if (!root.path("status").asBoolean(false)) return java.util.List.of();
            com.fasterxml.jackson.databind.JsonNode data = root.path("data");
            java.util.List<java.util.Map<String, Object>> list = new java.util.ArrayList<>();
            if (data.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode n : data) {
                    java.util.Map<String, Object> m = new java.util.HashMap<>();
                    m.put("name", n.path("name").asText(""));
                    m.put("code", n.path("code").asText(""));
                    m.put("slug", n.path("slug").asText(""));
                    m.put("currency", n.path("currency").asText(""));
                    list.add(m);
                }
            }
            return list;
        }
        return null; // non-2xx treated as failure so callers can fallback to cache
    }

    // Built-in minimal fallback list used only when upstream and cache are unavailable.
    // Contains common SA bank names to allow FE to render a picker; codes/slugs are left empty.
    private java.util.List<java.util.Map<String, Object>> defaultSABanks() {
        java.util.List<java.util.Map<String, Object>> list = new java.util.ArrayList<>();
        list.add(java.util.Map.of("name", "First National Bank (FNB)", "code", "", "slug", "", "currency", "ZAR"));
        list.add(java.util.Map.of("name", "ABSA", "code", "", "slug", "", "currency", "ZAR"));
        list.add(java.util.Map.of("name", "Standard Bank", "code", "", "slug", "", "currency", "ZAR"));
        list.add(java.util.Map.of("name", "Capitec Bank", "code", "", "slug", "", "currency", "ZAR"));
        list.add(java.util.Map.of("name", "Nedbank", "code", "", "slug", "", "currency", "ZAR"));
        return list;
    }

    private static String normalizeBankName(String s) {
        if (s == null) return null;
        String lower = s.toLowerCase();
        // replace common abbreviations
        lower = lower.replace("first national bank", "fnb");
        lower = lower.replace("standard bank", "standardbank");
        // strip non-alphanumerics
        StringBuilder sb = new StringBuilder();
        for (char c : lower.toCharArray()) { if (Character.isLetterOrDigit(c)) sb.append(c); }
        return sb.toString();
    }

    private String pickFrom(java.util.List<java.util.Map<String, Object>> banks, String keyword) {
        String key = normalizeBankName(keyword);
        for (java.util.Map<String, Object> b : banks) {
            String name = java.util.Objects.toString(b.get("name"), "");
            String slug = java.util.Objects.toString(b.get("slug"), "");
            String code = java.util.Objects.toString(b.get("code"), "");
            String nn = normalizeBankName(name);
            if (nn.contains(key) || (slug != null && slug.toLowerCase().contains(key))) {
                return !code.isBlank() ? code : (!slug.isBlank() ? slug : null);
            }
        }
        return null;
    }

    private String resolveSASettlementBank(String settlementBank, String bankName) throws Exception {
        String sb = trimToNull(settlementBank);
        if (sb != null) return sb;
        String bn = trimToNull(bankName);
        if (bn == null) return null;
        java.util.List<java.util.Map<String, Object>> banks = fetchSABanks();
        String bnNorm = normalizeBankName(bn);
        for (java.util.Map<String, Object> b : banks) {
            String name = java.util.Objects.toString(b.get("name"), "");
            String code = java.util.Objects.toString(b.get("code"), "");
            String slug = java.util.Objects.toString(b.get("slug"), "");
            String nNorm = normalizeBankName(name);
            if (nNorm.equals(bnNorm) || nNorm.contains(bnNorm) || bnNorm.contains(nNorm)) {
                return !code.isBlank() ? code : (!slug.isBlank() ? slug : null);
            }
            if (!slug.isBlank()) {
                String sn = normalizeBankName(slug);
                if (sn.equals(bnNorm) || bnNorm.contains(sn) || sn.contains(bnNorm)) {
                    return !code.isBlank() ? code : slug;
                }
            }
        }
        // common synonyms
        String fromSyn = null;
        if (bnNorm.contains("fnb")) fromSyn = pickFrom(banks, "first national bank");
        else if (bnNorm.contains("absa")) fromSyn = pickFrom(banks, "absa");
        else if (bnNorm.contains("standardbank")) fromSyn = pickFrom(banks, "standard bank");
        else if (bnNorm.contains("capitec")) fromSyn = pickFrom(banks, "capitec");
        else if (bnNorm.contains("nedbank")) fromSyn = pickFrom(banks, "nedbank");
        if (fromSyn != null) return fromSyn;
        return null;
    }

    @GetMapping("/paystack/account")
    public ResponseEntity<?> paystackAccount(Principal principal) {
        String uid = principal != null ? principal.getName() : null;
        if (uid == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        if (paystackConfig.getSecretKey() == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "error", true,
                    "reason", "paystack_not_configured",
                    "message", "Set PAYSTACK_SECRET_KEY or app.paystack.secretKey"
            ));
        }
        try {
            // Read provider->paystack.subaccountCode
            Object snap = firestoreService.get("providers", uid);
            Class<?> docSnapClass = Class.forName("com.google.cloud.firestore.DocumentSnapshot");
            Boolean exists = (Boolean) docSnapClass.getMethod("exists").invoke(snap);
            String subCode = null;
            if (Boolean.TRUE.equals(exists)) {
                @SuppressWarnings("unchecked") Map<String, Object> data = (Map<String, Object>) docSnapClass.getMethod("getData").invoke(snap);
                if (data != null && data.get("paystack") instanceof Map<?,?> m) {
                    Object sc = ((Map<?,?>) m).get("subaccountCode");
                    if (sc != null) subCode = String.valueOf(sc);
                }
            }
            if (subCode == null || subCode.isBlank()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                        "error", true,
                        "reason", "not_linked",
                        "message", "Provider is not linked to a Paystack subaccount."
                ));
            }

            // Fetch subaccount details
            Map<String, Object> subaccount = null;
            {
                java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(paystackConfig.getBaseUrl() + "/subaccount/" + subCode))
                        .header("Authorization", paystackConfig.getAuthHeader())
                        .GET()
                        .build();
                java.net.http.HttpResponse<String> resp = java.net.http.HttpClient.newHttpClient().send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                    com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(resp.body());
                    if (root.path("status").asBoolean(false)) {
                        com.fasterxml.jackson.databind.JsonNode data = root.path("data");
                        subaccount = new java.util.HashMap<>();
                        subaccount.put("id", data.path("id").asText(null));
                        subaccount.put("subaccount_code", data.path("subaccount_code").asText(null));
                        subaccount.put("business_name", data.path("business_name").asText(null));
                        subaccount.put("settlement_bank", data.path("settlement_bank").asText(null));
                        subaccount.put("account_number", data.path("account_number").asText(null));
                        subaccount.put("percentage_charge", data.path("percentage_charge").asDouble(0.0));
                        subaccount.put("is_verified", data.path("is_verified").asBoolean(false));
                        subaccount.put("active", data.path("active").asBoolean(true));
                    } else {
                        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                                "error", true,
                                "stage", "fetch_subaccount",
                                "message", root.path("message").asText("Failed to fetch subaccount")
                        ));
                    }
                } else {
                    return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                            "error", true,
                            "status", resp.statusCode(),
                            "stage", "fetch_subaccount",
                            "body", resp.body()
                    ));
                }
            }

            // Fetch recent transactions for this subaccount
            java.util.List<Map<String, Object>> transactions = new java.util.ArrayList<>();
            {
                String url = paystackConfig.getBaseUrl() + "/transaction?perPage=20&subaccount=" + java.net.URLEncoder.encode(subCode, java.nio.charset.StandardCharsets.UTF_8);
                java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(url))
                        .header("Authorization", paystackConfig.getAuthHeader())
                        .GET()
                        .build();
                java.net.http.HttpResponse<String> resp = java.net.http.HttpClient.newHttpClient().send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                    com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(resp.body());
                    if (root.path("status").asBoolean(false)) {
                        com.fasterxml.jackson.databind.JsonNode data = root.path("data");
                        if (data.isArray()) {
                            for (com.fasterxml.jackson.databind.JsonNode n : data) {
                                Map<String, Object> one = new java.util.HashMap<>();
                                one.put("id", n.path("id").asText(null));
                                one.put("reference", n.path("reference").asText(null));
                                one.put("amount", n.path("amount").asInt(0));
                                one.put("currency", n.path("currency").asText(null));
                                one.put("status", n.path("status").asText(null));
                                one.put("channel", n.path("channel").asText(null));
                                one.put("fees", n.path("fees").asInt(0));
                                one.put("paid_at", n.path("paid_at").asText(null));
                                one.put("created_at", n.path("created_at").asText(null));
                                transactions.add(one);
                            }
                        }
                    } // else ignore and return empty list
                } // else ignore and return empty list
            }

            // Fetch recent settlements for this subaccount (if available)
            java.util.List<Map<String, Object>> settlements = new java.util.ArrayList<>();
            {
                String url = paystackConfig.getBaseUrl() + "/settlement?perPage=20&subaccount=" + java.net.URLEncoder.encode(subCode, java.nio.charset.StandardCharsets.UTF_8);
                java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(url))
                        .header("Authorization", paystackConfig.getAuthHeader())
                        .GET()
                        .build();
                java.net.http.HttpResponse<String> resp = java.net.http.HttpClient.newHttpClient().send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                    com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(resp.body());
                    if (root.path("status").asBoolean(false)) {
                        com.fasterxml.jackson.databind.JsonNode data = root.path("data");
                        if (data.isArray()) {
                            for (com.fasterxml.jackson.databind.JsonNode n : data) {
                                Map<String, Object> one = new java.util.HashMap<>();
                                one.put("id", n.path("id").asText(null));
                                one.put("total_amount", n.path("total_amount").asInt(0));
                                one.put("currency", n.path("currency").asText(null));
                                one.put("settled_by", n.path("settled_by").asText(null));
                                one.put("settlement_date", n.path("settlement_date").asText(null));
                                one.put("status", n.path("status").asText(null));
                                settlements.add(one);
                            }
                        }
                    }
                }
            }

            Map<String, Object> balances = new java.util.HashMap<>();
            balances.put("available", 0);
            balances.put("pending", 0);
            balances.put("currency", "ZAR");
            balances.put("note", "Paystack does not expose subaccount balances; funds settle directly to the provider's bank.");

            Map<String, Object> out = new java.util.HashMap<>();
            out.put("subaccountCode", subCode);
            out.put("subaccount", subaccount);
            out.put("balances", balances);
            out.put("transactions", transactions);
            out.put("settlements", settlements);
            return ResponseEntity.ok(out);
        } catch (ClassNotFoundException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "error", true,
                    "reason", "firestore_sdk_missing",
                    "message", "Firestore SDK not available"
            ));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "error", true,
                    "reason", "exception",
                    "message", e.getMessage()
            ));
        }
    }

    @lombok.Data
    public static class PaystackSubaccountRequest {
        private String businessName; // Business/Provider name
        private String settlementBank; // OPTIONAL for SA: bank code or slug; auto-resolved if not provided
        private String bankName; // OPTIONAL: Human-readable bank name (e.g., "FNB" or "First National Bank"). Used to auto-resolve settlementBank for SA.
        private String accountNumber; // provider account number
        private Double percentageCharge; // optional: platform commission percent
        private String settlementSchedule; // optional: auto | weekly | monthly | manual
    }

}
