package com.logicnativesolution.servemeapi.dto.profile;

import lombok.Data;

import java.util.List;

@Data
public class CompleteProfileRequest {
    // ProviderDoc service types (e.g., ["plumber", "cleaner"]) 
    private List<String> serviceTypes;
    // Legacy single service identifier (optional for backward compatibility)
    private String serviceId;
    // Deprecated: Stripe linking info (ignored)
    private String stripeAccountId;
    private Boolean stripePayoutsEnabled;
    // Default address to store under users/{uid}.defaultAddress
    private String addressLine1;
    private Double addressLat;
    private Double addressLng;
    private String addressGeohash;

    // Paystack subaccount onboarding (optional but recommended)
    // If provided alongside accountNumber and either bankName or settlementBank, backend will auto-create Paystack subaccount.
    private String businessName;         // Provider business name as Paystack expects (business_name)
    private String bankName;             // Human-readable bank (e.g., "FNB", "First National Bank") used to resolve settlement_bank
    private String settlementBank;       // Paystack bank code/slug; overrides bankName resolution if provided
    private String accountNumber;        // Bank account number
    private String settlementSchedule;   // optional: auto | weekly | monthly | manual
}
