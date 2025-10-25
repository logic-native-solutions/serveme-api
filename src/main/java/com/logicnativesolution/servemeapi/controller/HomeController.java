package com.logicnativesolution.servemeapi.controller;

import com.logicnativesolution.servemeapi.entities.User;
import com.logicnativesolution.servemeapi.service.HomeService;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;

@RequiredArgsConstructor
@RestController
@Getter
@RequestMapping("/api/v1/home")
public class HomeController {
    private final HomeService homeService;

    @GetMapping("/user-details")
    public ResponseEntity<User> currentUser(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            return ResponseEntity.status(401).build();
        }

        return ResponseEntity.ok(homeService.getCurrentAuthenticatedUser(authentication));
    }
}
