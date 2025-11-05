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
public class ProviderDoc {
    private String userId;
    private List<String> serviceTypes; // ["plumber", "cleaner"]
    private Boolean isOnline;
    private Double lat;
    private Double lng;
    private String geohash;

    private Double ratingAvg;
    private Integer ratingCount;

    private Verified verified;

    private Instant createdAt;
    private Instant updatedAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Verified {
        private Boolean identity; // false by default
    }
}
