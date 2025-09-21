package com.logicnativesolution.servemeapi.dto;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;


@Data
@Service
@RequiredArgsConstructor
public class ResetPasswordEmailDto {
    private String email;
}
