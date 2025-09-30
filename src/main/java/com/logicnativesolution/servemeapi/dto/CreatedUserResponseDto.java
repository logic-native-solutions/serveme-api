package com.logicnativesolution.servemeapi.dto;

public record CreatedUserResponseDto(
        String token,
        String status,
        boolean isAuthorized,
        String message,
        UserView user
) { }
