package com.logicnativesolution.servemeapi.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ForgotPasswordDto {
    @NotBlank(message = "Email is required")
    private String codeFromEmail;

    @NotBlank(message = "New password is required")
    private String newPassword;

    @NotBlank(message = "Confirm new password is required")
    private String confirmNewPassword;

}
