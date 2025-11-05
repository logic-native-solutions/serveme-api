package com.logicnativesolution.servemeapi.controller;

import com.logicnativesolution.servemeapi.service.ProviderPaymentsService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.Map;

/**
 * Provider payments API (card link and withdrawals).
 * Business logic is in ProviderPaymentsService according to separation of concerns.
 */
@RestController
@RequestMapping("/api/v1/providers")
@RequiredArgsConstructor
public class ProviderPaymentsController {

    private final ProviderPaymentsService service;
    private final com.logicnativesolution.servemeapi.service.ProviderPayoutsService payoutsService;

    @PostMapping("/{uid}/paystack/link-card/init")
    public ResponseEntity<?> startCardLink(@PathVariable String uid, @RequestBody ProviderPaymentsService.LinkCardRequest req, Principal principal) {
        // If principal provided, ensure it matches uid
        if (principal != null && uid != null && !uid.equals(principal.getName())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "uid mismatch"));
        }
        Map<String, Object> res = service.initCardLink(uid, req);
        int code = (int) res.getOrDefault("_httpStatus", HttpStatus.OK.value());
        res.remove("_httpStatus");
        return ResponseEntity.status(code).body(res);
    }

    @GetMapping("/{uid}/paystack/payment-methods")
    public ResponseEntity<?> listPaymentMethods(@PathVariable String uid, Principal principal) {
        if (principal != null && uid != null && !uid.equals(principal.getName())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "uid mismatch"));
        }
        Map<String, Object> res = service.listPaymentMethods(uid);
        int code = (int) res.getOrDefault("_httpStatus", HttpStatus.OK.value());
        res.remove("_httpStatus");
        return ResponseEntity.status(code).body(res);
    }

    @PostMapping("/{uid}/paystack/withdraw")
    public ResponseEntity<?> withdraw(@PathVariable String uid, @RequestBody ProviderPaymentsService.WithdrawRequest req, Principal principal) {
        if (principal != null && uid != null && !uid.equals(principal.getName())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "uid mismatch"));
        }
        Map<String, Object> res = service.createWithdrawRequest(uid, req);
        int code = (int) res.getOrDefault("_httpStatus", HttpStatus.OK.value());
        res.remove("_httpStatus");
        return ResponseEntity.status(code).body(res);
    }
    @PostMapping("/{uid}/withdrawals/{withdrawalId}/approve")
    public ResponseEntity<?> approveWithdrawal(@PathVariable String uid, @PathVariable String withdrawalId, Principal principal) {
        if (principal != null && uid != null && !uid.equals(principal.getName())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "uid mismatch"));
        }
        Map<String, Object> res = payoutsService.approveWithdrawal(uid, withdrawalId);
        int code = (int) res.getOrDefault("_httpStatus", HttpStatus.OK.value());
        res.remove("_httpStatus");
        return ResponseEntity.status(code).body(res);
    }

    @PostMapping("/{uid}/withdrawals/{withdrawalId}/process")
    public ResponseEntity<?> processWithdrawal(@PathVariable String uid, @PathVariable String withdrawalId, Principal principal) {
        if (principal != null && uid != null && !uid.equals(principal.getName())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "uid mismatch"));
        }
        Map<String, Object> res = payoutsService.processWithdrawal(uid, withdrawalId);
        int code = (int) res.getOrDefault("_httpStatus", HttpStatus.OK.value());
        res.remove("_httpStatus");
        return ResponseEntity.status(code).body(res);
    }
}
