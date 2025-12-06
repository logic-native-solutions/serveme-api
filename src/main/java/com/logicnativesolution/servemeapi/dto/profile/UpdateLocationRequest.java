package com.logicnativesolution.servemeapi.dto.profile;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class UpdateLocationRequest {
    private double lat;
    private double lng;
    // Use boxed Boolean so null is possible when client doesn't send the field
    @JsonProperty("isOnline")
    private Boolean isOnline;
}