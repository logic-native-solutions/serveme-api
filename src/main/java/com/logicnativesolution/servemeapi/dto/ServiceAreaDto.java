package com.logicnativesolution.servemeapi.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ServiceAreaDto {

    @NotBlank(message = "Service area is required")
    private String Province;

    @NotBlank(message = "City is required")
    private String City;
}
