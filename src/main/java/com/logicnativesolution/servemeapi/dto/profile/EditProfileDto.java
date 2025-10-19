package com.logicnativesolution.servemeapi.dto.profile;

import lombok.Data;

@Data
public class EditProfileDto {
    private String firstName;
    private String lastName;
    private String email;
    private String phone;
}
