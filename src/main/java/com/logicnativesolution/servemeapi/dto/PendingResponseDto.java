package com.logicnativesolution.servemeapi.dto;

import java.util.Map;

public record PendingResponseDto(String status, Map<String, Boolean> isAuthorized) { }
