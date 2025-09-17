package com.logicnativesolution.servemeapi.dto;

import com.logicnativesolution.servemeapi.validation.Lowercase;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.time.LocalDate;

@Data
public class RegisterUsersDto {
    @NotBlank(message = "First name is required")
    @Size(min = 5, max = 50, message = "First name can be between 5-50 characters long")
    private String firstName;

    @NotBlank(message = "Last name is required")
    @Size(min = 5, max = 50, message = "Last name can be between 5-50 characters long")
    private String lastName;

    @NotBlank(message = "Gender is required")
    @Size(min = 4, max = 6, message = "Gender can be between 4-6 characters long")
    private String gender;

    @NotBlank(message = "Phone number is required")
    @Size(min = 10, max = 15, message = "Phone number can be between 10-15 digits")
    private String phoneNumber;

    @NotBlank(message = "Identification number is required")
    @Size(min = 13, max = 13, message = "Identification number must be 13")
    private String idNumber;

    @NotNull(message = "Date of birth is required")
    private LocalDate dateOfBirth;

    @Email
    @Lowercase(message = "Email must be in lowercase")
    @Pattern(regexp = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$", message = "Invalid email format")
    private String email;

    @NotBlank(message = "Password is required")
    @Pattern(regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@#$%^&+=!]).{8,}$",
            message = "Password must be at least 8 characters long, contain at least one uppercase letter, one lowercase letter, one number and one special character")
    private String password;
}
