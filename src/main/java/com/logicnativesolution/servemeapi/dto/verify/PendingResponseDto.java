package com.logicnativesolution.servemeapi.dto.verify;

import java.util.Map;

public record PendingResponseDto(String status, Map<String, Boolean> isAuthorized) { }
