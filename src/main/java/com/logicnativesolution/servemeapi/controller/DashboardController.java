package com.logicnativesolution.servemeapi.controller;

import com.logicnativesolution.servemeapi.entities.User;
import com.logicnativesolution.servemeapi.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final UserRepository userRepository;

    @GetMapping("/redirect")
    public ResponseEntity<?> redirect(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(401).build();
        }
        String email = authentication.getName();
        User user = userRepository.findByEmailIgnoreCase(email).orElse(null);
        String roleName = user != null && user.getRole() != null && user.getRole().getName() != null
                ? user.getRole().getName().trim().toUpperCase(Locale.ROOT)
                : "USER";

        String target;
        switch (roleName) {
            case "PROVIDER" -> target = "/dashboard/provider";
            case "CLIENT" -> target = "/dashboard/client";
            default -> target = "/dashboard"; // generic/common
        }

        Map<String, Object> body = new HashMap<>();
        body.put("role", roleName.toLowerCase(Locale.ROOT));
        body.put("target", target);
        return ResponseEntity.ok(body);
    }
}
