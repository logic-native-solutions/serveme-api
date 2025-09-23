package com.logicnativesolution.servemeapi.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import lombok.Data;

@Data
@Configuration
@ConfigurationProperties(prefix = "serveme.cookies")
public class CookieConfig {
    /** Whether to mark the refresh cookie as Secure + SameSite=Strict. */
    private boolean secure = false;
}