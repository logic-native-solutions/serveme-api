package com.logicnativesolution.servemeapi.model.firestore;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JobDoc {
    private String serviceType;
    private String clientId;
    private String status; // pending | assigned | enroute | arrived | in_progress | completed | canceled | expired
    private String description;
    private List<String> photos; // URLs
    private Address address;
    private DesiredTime desiredTime; // { type, when }
    private Price price;
    private Payment payment;
    private String assignedProviderId; // nullable

    private Instant createdAt;
    private Instant expiresAt;
    private Instant acceptedAt;
    private Instant startedAt;
    private Instant completedAt;

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Address {
        private String line1;
        private Double lat;
        private Double lng;
        private String geohash;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class DesiredTime {
        private String type; // asap | scheduled
        private Long when;   // epoch seconds
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Price {
        private String currency; // e.g., ZAR
        private Long subtotal;   // cents
        private Long fees;       // cents
        private Long total;      // cents
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Payment {
        private String paymentIntentId;
        private String status; // requires_capture | succeeded | canceled | etc.
    }
}
