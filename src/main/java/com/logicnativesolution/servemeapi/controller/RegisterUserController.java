package com.logicnativesolution.servemeapi.controller;

import com.logicnativesolution.servemeapi.dto.RegisterUsersDto;
import com.logicnativesolution.servemeapi.service.RegisterUserService;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@AllArgsConstructor
@RequestMapping("/api/auth")
public class RegisterUserController {
    private final RegisterUserService registerUserService;

    @PostMapping("/register")
    private ResponseEntity<?> registerUserRequest(@Valid @RequestBody RegisterUsersDto request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(registerUserService.registerUser(request));
    }
}
