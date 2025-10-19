package com.logicnativesolution.servemeapi.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class JwtTokenDto {
    // New canonical name expected by some frontends
    private String accessToken;

    // Backward-compatible constructor: accept a single token string
    public JwtTokenDto(String token) {
        this.accessToken = token;
    }

    // Backward-compat serialization: also expose "token" field mirroring accessToken
    @JsonProperty("token")
    public String getToken() {
        return accessToken;
    }
}
