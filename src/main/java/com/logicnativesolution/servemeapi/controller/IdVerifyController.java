package com.logicnativesolution.servemeapi.controller;

import com.logicnativesolution.servemeapi.dto.FaceIdDecision;
import com.logicnativesolution.servemeapi.dto.RsaIdResult;
import com.logicnativesolution.servemeapi.repository.UserRepository;
import com.logicnativesolution.servemeapi.service.AryaRsaIdService;
import com.logicnativesolution.servemeapi.validation.SaIdRules;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/documents/rsa-id")
@RequiredArgsConstructor
public class IdVerifyController {
    private final UserRepository userRepository;
    private FaceIdDecision faceIdDecision;
    private AuthenticationUserController authenticationUserController;
    private final AryaRsaIdService aryaService;

    @PostMapping(value = "/front", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> verifyFront(
            @RequestParam("frontIdImage") MultipartFile front
    ) throws IOException {

        if (front == null || front.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error","front image required"));
        }
        if (front.getSize() > 8 * 1024 * 1024) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(Map.of("error","file too large"));
        }

        String ct = front.getContentType();
        if (ct == null || !(ct.equals("image/jpeg") || ct.equals("image/png"))) {
            return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                    .body(Map.of("error","only jpeg/png allowed"));
        }

        RsaIdResult extracted = aryaService.extractFromFront(front);
        // Compute field mismatches against the authenticated user (no throwing)
        String currentUserId = null;
        try {
            currentUserId = String.valueOf(authenticationUserController.getCurrentUser().getUser().getId());
        } catch (Exception ignored) {}

        List<String> mismatches = Collections.emptyList();
        if (currentUserId != null && !currentUserId.isBlank()) {
            mismatches = aryaService.computeMismatchesForUser(currentUserId, extracted);
        }

        // run local checks
        boolean idFormatValid = extracted.getIdNumber() != null && SaIdRules.isValidSouthAfricanId(extracted.getIdNumber());
        boolean ocrConfOk = extracted.getOcrConfidence() == null || extracted.getOcrConfidence() >= 0.75f;
        String decision = (idFormatValid && ocrConfOk && (mismatches == null || mismatches.isEmpty()))
                ? "AUTO_PASS"
                : "MANUAL_REVIEW";

        faceIdDecision.setIdDecision(decision);

        Map<String,Object> verdict = Map.of(
                "extracted", extracted,
                "checks", Map.of(
                        "idFormatValid", idFormatValid,
                        "ocrConfidenceOk", ocrConfOk
                ),
                "mismatches", mismatches,
                "decision", decision
        );

        // Persist submission (DB) and store raw JSON + file location for audit (recommended)
        return ResponseEntity.ok(verdict);



    }


    @PostMapping(value = "/face-verify", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> faceVerify(
            @RequestParam("frontIdImage") MultipartFile front,
            @RequestParam("selfieWithId") MultipartFile selfie
    ) throws IOException {
        // Validate inputs
        if (front == null || front.isEmpty() || selfie == null || selfie.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error","frontIdImage and selfieWithId are required"));
        }
        if (front.getSize() > 8 * 1024 * 1024 || selfie.getSize() > 8 * 1024 * 1024) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(Map.of("error","file too large"));
        }
        String ct1 = front.getContentType();
        String ct2 = selfie.getContentType();
        if ((ct1 == null || !(ct1.equals("image/jpeg") || ct1.equals("image/png")))
                || (ct2 == null || !(ct2.equals("image/jpeg") || ct2.equals("image/png")))) {
            return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                    .body(Map.of("error","only jpeg/png allowed"));
        }

        // Use Step 2 service: compare stored front (here we pass the front bytes directly) with the selfie
        Map<String, Object> res = aryaService.compareStoredFrontWithSelfie(front.getBytes(), selfie);

        Double similarity = (Double) res.get("similarity");
        double threshold = 0.85; // tune 0.80–0.90 based on your risk appetite

        String decision = (similarity != null && similarity >= threshold) ? "AUTO_PASS" : "MANUAL_REVIEW";

        faceIdDecision.setFaceDecision(decision);

        if (faceIdDecision.getFaceDecision().equals("AUTO_PASS") && faceIdDecision.getIdDecision().equals("AUTO_PASS")) {
            var user = userRepository.findById(authenticationUserController.getCurrentUser().getUser().getId()).orElse(null);
            if (user == null) {
                return ResponseEntity.badRequest().body(Map.of("error","user not found"));
            }

            user.setVerified(true);
        }

        return ResponseEntity.ok(Map.of(
                "similarity", similarity,
                "threshold", threshold,
                "decision", decision,
                "raw", res.get("compareRaw")
        ));
    }
}