package com.logicnativesolution.servemeapi.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class UpdateOtpChannelDto {
    private String sessionId;
    private String channel;
    private String destination;
}
