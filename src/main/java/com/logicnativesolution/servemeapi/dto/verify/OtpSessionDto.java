package com.logicnativesolution.servemeapi.dto.verify;

import lombok.Data;

@Data
public class OtpSessionDto {
    private String sessionId;
    private String channel;
    private String destination;
}
