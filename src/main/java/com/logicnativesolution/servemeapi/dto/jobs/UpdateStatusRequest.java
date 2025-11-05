package com.logicnativesolution.servemeapi.dto.jobs;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UpdateStatusRequest {
    @NotBlank(message = "status is required")
    private String status;
}
