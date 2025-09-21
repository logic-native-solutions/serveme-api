package com.logicnativesolution.servemeapi.config;


import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@Data
@ConfigurationProperties(prefix = "arya")
public class AryaProperties {
    private String baseUrl;
    private String rsaidPath;
    private String idApiKey;
    private String faceApiKey;
    private String faceVerifyPath;
    private final int timeoutMs = 30000;
}