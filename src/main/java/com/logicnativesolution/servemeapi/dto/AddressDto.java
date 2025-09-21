package com.logicnativesolution.servemeapi.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.UUID;

@Data
public class AddressDto {

    private UUID userId;  //for testing purposes

    @NotBlank(message = "Address is required")
    private String province;

    @NotBlank(message = "City is required")
    private String city;

    @NotBlank(message = "Street is required")
    private String street;

    @NotBlank(message = "Postal code is required")
    private String postalCode;
}
