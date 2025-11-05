package com.logicnativesolution.servemeapi.dto.jobs;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class SendMessageRequest {
    @NotBlank(message = "text is required")
    private String text;
    private String type; // text | image
}
