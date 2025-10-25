package com.logicnativesolution.servemeapi.controller;

import com.logicnativesolution.servemeapi.config.PaystackConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Public, non-authenticated endpoints exposing safe Paystack configuration for FE SDK usage.
 */
@RestController
@RequestMapping("/api/v1/paystack")
@RequiredArgsConstructor
public class PublicPaystackController {

    private final PaystackConfig paystackConfig;

    @GetMapping("/public-key")
    public ResponseEntity<?> getPublicKey() {
        String pk = paystackConfig.getPublicKey();
        if (pk == null || pk.isBlank()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of(
                            "error", "Paystack public key not configured",
                            "message", "Set PAYSTACK_PUBLIC_KEY or app.paystack.publicKey"
                    ));
        }
        return ResponseEntity.ok(Map.of("publicKey", pk));
    }
}
