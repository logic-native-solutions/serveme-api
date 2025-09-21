package com.logicnativesolution.servemeapi.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Map;

@Data
public class RsaIdResult {
    private String idNumber;
    private String firstName;
    private String lastName;
    private String fullName;
    private String dateOfBirth;       // map to LocalDate later if possible
    private Float ocrConfidence;// optional
    private String gender;
    private Map<String, Object> raw;  // keep raw JSON for audit/debugging

}
