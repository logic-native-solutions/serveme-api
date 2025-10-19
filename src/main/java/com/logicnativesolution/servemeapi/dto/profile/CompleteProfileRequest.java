package com.logicnativesolution.servemeapi.dto.profile;

import lombok.Data;

import java.util.List;

@Data
public class CompleteProfileRequest {
    // ProviderDoc service types (e.g., ["plumber", "cleaner"])
    private List<String> serviceTypes;
    // Legacy single service identifier (optional for backward compatibility)
    private String serviceId;
    // Stripe linking info
    private String stripeAccountId;
    private Boolean stripePayoutsEnabled;
    // Default address to store under users/{uid}.defaultAddress
    private String addressLine1;
    private Double addressLat;
    private Double addressLng;
    private String addressGeohash;
}
