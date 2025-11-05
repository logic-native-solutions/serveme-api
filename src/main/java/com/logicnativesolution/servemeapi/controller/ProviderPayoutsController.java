package com.logicnativesolution.servemeapi.controller;

import com.logicnativesolution.servemeapi.service.ProviderPayoutsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.Map;

/**
 * Provider payouts execution endpoints (deprecated controller).
 * Delegated endpoints now live in ProviderPaymentsController to avoid duplicate mappings.
 */
@Deprecated
public class ProviderPayoutsController {

    private ProviderPayoutsService payoutsService;

    @PostMapping("/{uid}/withdrawals/{withdrawalId}/approve")
    public ResponseEntity<?> approve(@PathVariable String uid,
                                     @PathVariable String withdrawalId,
                                     Principal principal) {
        if (principal != null && uid != null && !uid.equals(principal.getName())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "uid mismatch"));
        }
        Map<String, Object> res = payoutsService.approveWithdrawal(uid, withdrawalId);
        int code = (int) res.getOrDefault("_httpStatus", HttpStatus.OK.value());
        res.remove("_httpStatus");
        return ResponseEntity.status(code).body(res);
    }

    @PostMapping("/{uid}/withdrawals/{withdrawalId}/process")
    public ResponseEntity<?> process(@PathVariable String uid,
                                     @PathVariable String withdrawalId,
                                     Principal principal) {
        if (principal != null && uid != null && !uid.equals(principal.getName())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "uid mismatch"));
        }
        Map<String, Object> res = payoutsService.processWithdrawal(uid, withdrawalId);
        int code = (int) res.getOrDefault("_httpStatus", HttpStatus.OK.value());
        res.remove("_httpStatus");
        return ResponseEntity.status(code).body(res);
    }
}
