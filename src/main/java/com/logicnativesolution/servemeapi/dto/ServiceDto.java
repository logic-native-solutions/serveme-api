package com.logicnativesolution.servemeapi.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ServiceDto {

    @NotBlank(message = "Service name is required")
    private String name;

    @NotBlank(message = "Service description is required")
    private String description;
}
