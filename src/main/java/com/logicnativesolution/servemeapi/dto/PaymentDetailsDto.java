package com.logicnativesolution.servemeapi.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.LocalDate;

@Data
public class PaymentDetailsDto {

    @NotBlank(message = "Card number is required")
    private String cardNumber;

    @NotBlank(message = "Bank name is required")
    private String bankName;

    @NotBlank(message = "Account number is required")
    private String accountNumber;

    @NotBlank(message = "Card holder name is required")
    private String cardHolderName;

    @NotBlank(message = "Expiry date is required")
    private LocalDate expiryDate;

    @NotBlank(message = "CVV is required")
    private String cvv;
}
