package com.logicnativesolution.servemeapi.controller;

import com.logicnativesolution.servemeapi.dto.RegisterUsers;
import com.logicnativesolution.servemeapi.entities.User;
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
@RequestMapping("/api/register")
public class RegisterUserController {
    private final RegisterUserService registerUserService;

    @PostMapping("/account")
    private ResponseEntity<User> registerUserRequest(@Valid @RequestBody RegisterUsers request) {
        var registeredUser = registerUserService.registerUser(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(registeredUser);
    }
}
