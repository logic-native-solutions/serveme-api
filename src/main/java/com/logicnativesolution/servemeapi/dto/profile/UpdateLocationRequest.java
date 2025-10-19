package com.logicnativesolution.servemeapi.dto.profile;

import lombok.Data;

@Data
public class UpdateLocationRequest {
    private double lat;
    private double lng;
    private boolean isOnline;
}