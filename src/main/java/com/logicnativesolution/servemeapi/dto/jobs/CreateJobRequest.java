package com.logicnativesolution.servemeapi.dto.jobs;

import lombok.Data;

import java.util.List;

@Data
public class CreateJobRequest {
    private String serviceType;
    private String description;
    private List<String> photoUrls;
    private Address address;
    private DesiredTime desiredTime; // { type, when }
    private String paymentMethodId; // reserved for future Stripe usage
    private List<String> addOnIds;
    private String currency; // default ZAR

    @Data
    public static class Address {
        private String line1;
        private Double lat;
        private Double lng;
    }

    @Data
    public static class DesiredTime {
        private String type;
        private Long when;
        
    }
}
