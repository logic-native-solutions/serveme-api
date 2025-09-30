package com.logicnativesolution.servemeapi.dto;

import java.util.UUID;

public record UserView(
        UUID id,
        String firstName,
        String lastName,
        String email,
        String role
) {}
