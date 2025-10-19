package com.logicnativesolution.servemeapi.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Initializes Paystack configuration from properties/env.
 *
 * Sources (in order):
 * - app.paystack.secretKey
 * - paystack.secretKey
 * - PAYSTACK_SECRET_KEY (env)
 * - System property paystack.secretKey
 *
 * Also exposes a platform commission percent controlled by the app owner
 * (not user-editable): app.paystack.commissionPercent with fallbacks.
 */
@Component
public class PaystackConfig implements InitializingBean {
    private static final Logger log = LoggerFactory.getLogger(PaystackConfig.class);

    private final Environment env;

    public PaystackConfig(Environment env) {
        this.env = env;
    }

    private static String trimToNull(String s) { return (s == null || s.isBlank()) ? null : s.trim(); }

    private String secretKey;
    private String baseUrl;
    private Double commissionPercent;

    public String getSecretKey() { return secretKey; }
    public String getBaseUrl() { return baseUrl != null ? baseUrl : "https://api.paystack.co"; }

    public String getAuthHeader() { return "Bearer " + secretKey; }
    public Double getCommissionPercent() { return commissionPercent; }

    @Override
    public void afterPropertiesSet() {
        String key = trimToNull(env.getProperty("app.paystack.secretKey"));
        if (key == null) key = trimToNull(env.getProperty("paystack.secretKey"));
        if (key == null) key = trimToNull(System.getenv("PAYSTACK_SECRET_KEY"));
        if (key == null) key = trimToNull(System.getProperty("paystack.secretKey"));
        this.secretKey = key;
        this.baseUrl = trimToNull(env.getProperty("app.paystack.baseUrl"));

        // Commission percent (owner-controlled)
        Double cp = null;
        try {
            String v = trimToNull(env.getProperty("app.paystack.commissionPercent"));
            if (v == null) v = trimToNull(System.getenv("PLATFORM_COMMISSION_PERCENT"));
            if (v == null) v = trimToNull(System.getenv("SERVEME_COMMISSION_PERCENT"));
            // fallback to deprecated stripe.applicationFeePercent for migration
            if (v == null) v = trimToNull(env.getProperty("stripe.applicationFeePercent"));
            if (v != null) cp = Double.parseDouble(v);
        } catch (Exception ignore) {}
        if (cp == null) cp = 15.0; // sensible default
        this.commissionPercent = cp;

        if (secretKey == null) {
            log.warn("PaystackConfig: Secret key not set (checked app.paystack.secretKey, paystack.secretKey, PAYSTACK_SECRET_KEY, system property). Paystack calls will fail.");
        } else {
            log.info("PaystackConfig: Secret key configured. Base URL: {}", getBaseUrl());
        }
        log.info("Platform commissionPercent set to {}% (owner-controlled)", this.commissionPercent);
    }
}
