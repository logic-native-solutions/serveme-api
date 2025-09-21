package com.logicnativesolution.servemeapi.dto;

import com.logicnativesolution.servemeapi.entities.User;

public record SimpleResponseDto(String status, String message, User user) { }
