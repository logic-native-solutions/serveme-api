package com.logicnativesolution.servemeapi.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Component;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Component
public class OtpCodesDto {
    private String phoneOtp;
    private String emailOtp;
    private boolean smsVerified;
    private boolean emailVerified;
}
