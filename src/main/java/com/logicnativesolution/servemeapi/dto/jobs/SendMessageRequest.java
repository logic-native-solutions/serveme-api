package com.logicnativesolution.servemeapi.dto.jobs;

import lombok.Data;

@Data
public class SendMessageRequest {
    private String text;
    private String type; // text | image
}
