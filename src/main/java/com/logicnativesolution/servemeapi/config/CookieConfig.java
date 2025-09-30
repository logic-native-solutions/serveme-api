package com.logicnativesolution.servemeapi.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "serveme.cookie")
public class CookieConfig {
    private String name = "refresh_token";
    private boolean secure = false;
    private boolean httpOnly = true;
    private String sameSite = "Lax";
    private String path = "/api/auth/refresh";
    private long maxAgeSeconds = 2592000;
}
