package com.logicnativesolution.servemeapi.dto.verify;

import lombok.Data;

@Data
public class VerifyOtpDto {
    private String sessionId;
    private String channel;
    private String code;
}
