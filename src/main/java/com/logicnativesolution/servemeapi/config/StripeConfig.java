package com.logicnativesolution.servemeapi.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import jakarta.annotation.PostConstruct;

/**
 * Initializes Stripe API key at startup if the Stripe SDK is on the classpath.
 * Order of precedence for key resolution:
 * 1) Spring property: app.stripe.secretKey or stripe.secretKey
 * 2) OS env: STRIPE_SECRET_KEY
 * 3) JVM system property: stripe.secretKey
 */
@Configuration
public class StripeConfig {
    private static final Logger log = LoggerFactory.getLogger(StripeConfig.class);

    @Autowired(required = false)
    private Environment env;

    @PostConstruct
    public void init() {
        String key = null;
        if (env != null) {
            key = trimToNull(env.getProperty("app.stripe.secretKey"));
            if (key == null) key = trimToNull(env.getProperty("stripe.secretKey"));
        }
        if (key == null) key = trimToNull(System.getenv("STRIPE_SECRET_KEY"));
        if (key == null) key = trimToNull(System.getProperty("stripe.secretKey"));

        if (key == null) {
            log.warn("StripeConfig: Secret key not set (checked app.stripe.secretKey, stripe.secretKey, STRIPE_SECRET_KEY). Stripe features will be disabled.");
            return;
        }
        try {
            Class<?> stripe = Class.forName("com.stripe.Stripe");
            try {
                // Prefer public setter if present
                stripe.getMethod("setApiKey", String.class).invoke(null, key);
                log.info("StripeConfig: Stripe API key initialized via setApiKey() method");
            } catch (NoSuchMethodException nsme) {
                // Fallback to public field (older SDKs)
                stripe.getField("apiKey").set(null, key);
                log.info("StripeConfig: Stripe API key initialized via apiKey field");
            }
        } catch (ClassNotFoundException e) {
            log.warn("StripeConfig: Stripe SDK not on classpath; skipping initialization");
        } catch (ReflectiveOperationException e) {
            log.warn("StripeConfig: Failed to set Stripe API key via reflection", e);
        }
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
