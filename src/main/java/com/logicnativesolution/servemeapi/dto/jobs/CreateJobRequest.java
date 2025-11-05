package com.logicnativesolution.servemeapi.dto.jobs;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class CreateJobRequest {
    @NotBlank(message = "serviceType is required")
    private String serviceType;
    private String description;
    private List<String> photoUrls;
    @Valid
    @NotNull(message = "address is required")
    private Address address;
    private DesiredTime desiredTime; // { type, when }
    private String paymentMethodId; // reserved, unused for Paystack flow
    private List<String> addOnIds;
    private String currency; // default ZAR

    @Data
    public static class Address {
        @NotBlank(message = "address.line1 is required")
        private String line1;
        @NotNull(message = "address.lat is required")
        private Double lat;
        @NotNull(message = "address.lng is required")
        private Double lng;
    }

    @Data
    public static class DesiredTime {
        private String type; // now | asap | scheduled
        private Long when;   // epoch millis when type == scheduled
    }
}
