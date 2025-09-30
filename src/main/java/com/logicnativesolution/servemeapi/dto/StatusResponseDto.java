package com.logicnativesolution.servemeapi.dto;

import com.logicnativesolution.servemeapi.entities.User;

public record StatusResponseDto(String status, String message, User user) { }
