package com.logicnativesolution.servemeapi.model.firestore;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ServiceDoc {
    /**
     * Firestore document id (e.g., "plumber"). Not stored as a field in the doc itself, but
     * attached by our FirestoreService when listing collections.
     */
    private String id;

    private String displayName;
    /**
     * Store prices in the smallest currency unit (e.g., cents) to avoid floating point rounding errors.
     */
    private Long basePrice; // cents
    private List<AddOn> addOns; // optional
    private Integer minRadiusKm;
    private Integer maxRadiusKm;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AddOn {
        private String id;
        private String label;
        private Long price; // cents
    }
}
