package com.logicnativesolution.servemeapi.model.firestore;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserDoc {
    private String role; // "client" | "provider"
    private String firstName;
    private String lastName;
    private String email;
    private String phone;
    private String photoUrl;
    private Address defaultAddress;
    private Instant createdAt;
    private Instant updatedAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Address {
        private String line1;
        private Double lat;
        private Double lng;
        private String geohash;
    }
}
