package com.logicnativesolution.servemeapi.dto.user;

public record CreatedUserResponseDto(
        String token,
        String status,
        boolean isAuthorized,
        String message,
        UserView user
) { }
