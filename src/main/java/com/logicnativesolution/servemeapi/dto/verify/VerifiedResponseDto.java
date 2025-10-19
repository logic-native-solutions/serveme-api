package com.logicnativesolution.servemeapi.dto.verify;

import com.logicnativesolution.servemeapi.entities.User;

import java.util.Map;

public record VerifiedResponseDto(
        String status,
        Map<String, Boolean> isAuthorized,
        String message,
        User user
) { }
