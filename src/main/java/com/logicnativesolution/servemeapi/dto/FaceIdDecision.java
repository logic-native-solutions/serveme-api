package com.logicnativesolution.servemeapi.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.stereotype.Service;

@Data
@Service
public class FaceIdDecision {
    private String idDecision;
    private String FaceDecision;
}
