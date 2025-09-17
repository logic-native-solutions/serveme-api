package com.logicnativesolution.servemeapi.dto;

import lombok.Data;

@Data
public class OtpSessionDto {
    private String sessionId;
    private String channel;
    private String destination;
}
