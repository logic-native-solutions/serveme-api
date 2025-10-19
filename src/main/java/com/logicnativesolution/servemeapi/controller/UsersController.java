package com.logicnativesolution.servemeapi.controller;

import com.logicnativesolution.servemeapi.service.FirestoreService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UsersController {

    private final FirestoreService firestoreService;

    @PostMapping("/me/token")
    public ResponseEntity<?> registerFcmToken(@RequestBody Map<String, String> body, Principal principal) {
        String uid = principal != null ? principal.getName() : null;
        if (uid == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        String token = body != null ? body.get("token") : null;
        if (token == null || token.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "token is required"));
        }
        try {
            Map<String, Object> update = new HashMap<>();
            // Store latest token (simple MVP). Later you can switch to a list of tokens.
            update.put("fcmToken", token);
            update.put("updatedAt", java.time.Instant.now().toString());
            firestoreService.set("users", uid, update);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Failed to register FCM token");
        }
    }
}
