package com.logicnativesolution.servemeapi.dto.verify;

import com.logicnativesolution.servemeapi.entities.User;

public record StatusResponseDto(String status, String message, User user) { }
