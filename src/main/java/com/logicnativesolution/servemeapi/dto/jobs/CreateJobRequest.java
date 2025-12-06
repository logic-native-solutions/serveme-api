package com.logicnativesolution.servemeapi.dto.jobs;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;
import java.util.Map;

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

    // --- Compatibility setters ---
    // Some apps may send location/address in alternative shapes. The following setters
    // build the nested Address object so validation passes while keeping API strictness.

    // Accept a top-level alias for address object named "location"
    @JsonProperty("location")
    public void setLocationCompat(Map<String, Object> location) {
        if (location == null) return;
        ensureAddress();
        // Support keys: line1 | addressLine1 | street; lat | latitude; lng | longitude
        Object line1 = firstNonNull(location.get("line1"), location.get("addressLine1"), location.get("street"));
        Object lat = firstNonNull(location.get("lat"), location.get("latitude"));
        Object lng = firstNonNull(location.get("lng"), location.get("longitude"));
        if (line1 instanceof String s) this.address.setLine1(s);
        if (lat instanceof Number n) this.address.setLat(n.doubleValue());
        if (lng instanceof Number n) this.address.setLng(n.doubleValue());
        // In case strings arrive for coordinates
        if (lat instanceof String s) trySetLatFromString(s);
        if (lng instanceof String s) trySetLngFromString(s);
    }

    // Accept flat aliases: addressLine1, latitude, longitude
    @JsonProperty("addressLine1")
    public void setAddressLine1Compat(String line1) {
        if (line1 == null) return;
        ensureAddress();
        this.address.setLine1(line1);
    }

    @JsonProperty("latitude")
    public void setLatitudeCompat(Object latitude) {
        if (latitude == null) return;
        ensureAddress();
        if (latitude instanceof Number n) this.address.setLat(n.doubleValue());
        else if (latitude instanceof String s) trySetLatFromString(s);
    }

    @JsonProperty("longitude")
    public void setLongitudeCompat(Object longitude) {
        if (longitude == null) return;
        ensureAddress();
        if (longitude instanceof Number n) this.address.setLng(n.doubleValue());
        else if (longitude instanceof String s) trySetLngFromString(s);
    }

    private void ensureAddress() {
        if (this.address == null) this.address = new Address();
    }

    private static Object firstNonNull(Object... arr) {
        if (arr == null) return null;
        for (Object o : arr) if (o != null) return o;
        return null;
    }

    private void trySetLatFromString(String s) {
        try {
            this.address.setLat(Double.parseDouble(s));
        } catch (Exception ignored) { }
    }

    private void trySetLngFromString(String s) {
        try {
            this.address.setLng(Double.parseDouble(s));
        } catch (Exception ignored) { }
    }

    @Data
    public static class Address {
        @JsonAlias({"addressLine1", "street"})
        @NotBlank(message = "address.line1 is required")
        private String line1;
        @JsonAlias({"latitude"})
        @NotNull(message = "address.lat is required")
        private Double lat;
        @JsonAlias({"longitude"})
        @NotNull(message = "address.lng is required")
        private Double lng;
    }

    @Data
    public static class DesiredTime {
        private String type; // now | asap | scheduled
        private Long when;   // epoch millis when type == scheduled
    }
}
