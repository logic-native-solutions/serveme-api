package com.logicnativesolution.servemeapi.controller;

import com.logicnativesolution.servemeapi.entities.User;
import com.logicnativesolution.servemeapi.repository.UserRepository;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

@RequiredArgsConstructor
@RestController
@Getter
@RequestMapping("/api/v1/home")
public class HomeController {
    private final UserRepository userRepository;
    private String currentUserEmail = "";

    @GetMapping("/user-details")
    public ResponseEntity<User> currentUser(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            return ResponseEntity.status(401).build();
        }

        currentUserEmail = authentication.getName();
        var user = userRepository.findByEmail(currentUserEmail)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + currentUserEmail));

        return ResponseEntity.ok(user);
    }
}
