package com.logicnativesolution.servemeapi.dto.verify;

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
