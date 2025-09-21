package com.logicnativesolution.servemeapi.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.logicnativesolution.servemeapi.config.AryaProperties;
import com.logicnativesolution.servemeapi.dto.RsaIdResult;
import com.logicnativesolution.servemeapi.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

@Service
@RequiredArgsConstructor
public class AryaRsaIdService {
    private final WebClient aryaWebClient;
    private final AryaProperties props;
    private final ObjectMapper mapper; // Jackson
    private final UserRepository userRepository;

    @jakarta.annotation.PostConstruct
    void checkTokens() {
        if (props.getIdApiKey() == null || props.getIdApiKey().isBlank()) {
            throw new IllegalStateException("Missing Arya ID OCR token (arya.id-api-key / ARYA_ID_API_KEY)");
        }
        if (props.getFaceApiKey() == null || props.getFaceApiKey().isBlank()) {
            throw new IllegalStateException("Missing Arya face token (arya.face-api-key / ARYA_FACE_API_KEY)");
        }
    }

    private static String toBase64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    public RsaIdResult extractFromFront(MultipartFile frontImage) throws IOException {
        if (frontImage == null || frontImage.isEmpty()) {
            throw new IllegalArgumentException("front image is required");
        }

        // 1) Convert to Base64 as Arya expects JSON payload
        byte[] bytes = frontImage.getBytes();
        String base64 = Base64.getEncoder().encodeToString(bytes);

        Map<String, Object> payload = Map.of(
            "doc_base64", base64,
            "req_id", UUID.randomUUID().toString()
        );

        // 2) Call Arya JSON endpoint (headers: token + application/json)
        String respJson = aryaWebClient.post()
                .uri(props.getRsaidPath())
                .header("token", props.getIdApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, r -> r.bodyToMono(String.class)
                        .map(body -> new RuntimeException("Arya 4xx: " + body)))
                .onStatus(HttpStatusCode::is5xxServerError, r -> r.bodyToMono(String.class)
                        .map(body -> new RuntimeException("Arya 5xx: " + body)))
                .bodyToMono(String.class)
                .block();

        // 3) Parse generic JSON
        Map<String, Object> raw = mapper.readValue(respJson, new TypeReference<>(){});

        // 4) Try to extract commonly named fields if present
        String idNumber = null, firstName = null, lastName = null, fullName = null, dob = null, gender = null;
        Float ocrConfidence = null;


        if (raw.containsKey("idNumber")) idNumber = Objects.toString(raw.get("idNumber"), null);
        if (raw.containsKey("firstName")) firstName = Objects.toString(raw.get("firstName"), null);
        if (raw.containsKey("lastName")) lastName = Objects.toString(raw.get("lastName"), null);
        if (raw.containsKey("fullName")) fullName = Objects.toString(raw.get("fullName"), null);
        if (raw.containsKey("dateOfBirth")) dob = Objects.toString(raw.get("dateOfBirth"), null);
        if (raw.containsKey("sex")) gender = Objects.toString(raw.get("sex"), null);

        // Arya payload example shows fields under "extraction" using snake_case.
        Object extractionObj = raw.get("extraction");
        if (extractionObj instanceof Map<?,?> emap) {
            Object v;
            if (idNumber == null && (v = emap.get("identity_number")) != null) idNumber = Objects.toString(v, null);
            if (firstName == null && (v = emap.get("name")) != null) firstName = Objects.toString(v, null);
            if (lastName == null && (v = emap.get("surname")) != null) lastName = Objects.toString(v, null);
            if (gender == null && (v = emap.get("gender")) != null) gender = Objects.toString(v, null);
            // If fullName isn't provided, synthesize it from name + surname
            if (fullName == null) {
                String fn = firstName == null ? "" : firstName.trim();
                String ln = lastName == null ? "" : lastName.trim();
                String composed = (fn + " " + ln).trim();
                if (!composed.isBlank()) fullName = composed;
            }
            if (dob == null && (v = emap.get("date_of_birth")) != null) dob = Objects.toString(v, null);
        }

        // OCR/document confidence may be under "document_type" → "Document_Confidence"
        Object docTypeObj = raw.get("document_type");
        if (ocrConfidence == null && docTypeObj instanceof Map<?,?> dtyp) {
            Object dv = dtyp.get("Document_Confidence");
            if (dv != null) {
                try { ocrConfidence = Float.valueOf(dv.toString()); } catch (Exception ignored) {}
            }
        }

        // Some Arya responses nest data under "data". If so, shallow-merge likely fields.
        Object dataObj = raw.get("data");
        if (dataObj instanceof Map<?,?> dmap) {
            Object v;
            if (idNumber == null && (v = dmap.get("idNumber")) != null) idNumber = Objects.toString(v, null);
            if (firstName == null && (v = dmap.get("firstName")) != null) firstName = Objects.toString(v, null);
            if (lastName == null && (v = dmap.get("lastName")) != null) lastName = Objects.toString(v, null);
            if (fullName == null && (v = dmap.get("fullName")) != null) fullName = Objects.toString(v, null);
            if (dob == null && (v = dmap.get("dateOfBirth")) != null) dob = Objects.toString(v, null);
            if (gender == null && (v = dmap.get("gender")) != null) gender = Objects.toString(v, null);
            if ((v = dmap.get("ocrConfidence")) != null) {
                try { ocrConfidence = Float.valueOf(v.toString()); } catch (Exception ignored) {}
            }
        }

        RsaIdResult result = new RsaIdResult();
        result.setIdNumber(idNumber);
        result.setFirstName(firstName);
        result.setLastName(lastName);
        result.setFullName(fullName);
        result.setDateOfBirth(dob);
        result.setOcrConfidence(ocrConfidence);
        result.setGender(gender);
        result.setRaw(raw);
        return result;


    }

    public void verifyIdInfo(String userId, RsaIdResult extracted) {
        var user = userRepository.findById(UUID.fromString(userId)).orElse(null);
        if (user == null) {
            throw new RuntimeException("User not found");
        }

        // Name match (case-insensitive). Throw if either first or last name does not match.
        String uFirst = user.getFirstName() == null ? "" : user.getFirstName().trim();
        String uLast  = user.getLastName()  == null ? "" : user.getLastName().trim();
        String eFirst = extracted.getFirstName() == null ? "" : extracted.getFirstName().trim();
        String eLast  = extracted.getLastName()  == null ? "" : extracted.getLastName().trim();
        if (!uFirst.equalsIgnoreCase(eFirst) || !uLast.equalsIgnoreCase(eLast)) {
            throw new RuntimeException("First and/or Last name does not match");
        }

        // ID number exact match
        if (user.getIdNumber() == null || extracted.getIdNumber() == null || !user.getIdNumber().equals(extracted.getIdNumber())) {
            throw new RuntimeException("ID number does not match");
        }

        // Gender: compare first letter (M/F), case-insensitive
        String userGender = user.getGender() == null ? "" : user.getGender().trim();
        String extractedGender = extracted.getGender() == null ? "" : extracted.getGender().trim();
        String g1 = userGender.isEmpty() ? "" : userGender.substring(0, 1).toUpperCase();
        String g2 = extractedGender.isEmpty() ? "" : extractedGender.substring(0, 1).toUpperCase();
        if (!g1.equals(g2)) {
            throw new RuntimeException("Gender does not match");
        }

        // DOB: normalize formats and compare dates
        LocalDate userDob = null;
        if (user.getDateOfBirth() != null) {
            userDob = (user.getDateOfBirth() instanceof LocalDate ld) ? ld : LocalDate.parse(user.getDateOfBirth().toString());
        }
        LocalDate extDob = parseDob(extracted.getDateOfBirth());
        if (userDob != null && extDob != null && !userDob.equals(extDob)) {
            throw new RuntimeException("Date of birth does not match");
        }
    }

    private static LocalDate parseDob(String s) {
        if (s == null || s.isBlank()) return null;
        String str = s.trim();
        List<DateTimeFormatter> fmts = List.of(
                DateTimeFormatter.ofPattern("d MMM uuuu").withLocale(Locale.ENGLISH),
                DateTimeFormatter.ISO_LOCAL_DATE,
                DateTimeFormatter.ofPattern("dd/MM/uuuu")
        );
        for (var f : fmts) {
            try { return LocalDate.parse(str, f); } catch (DateTimeParseException ignored) {}
        }
        return null;
    }

    /**
     * Direct face verification using Arya's /api/v1/verifyFace endpoint.
     * Compares the stored front ID image (img1) with the user's selfie (img2).
     * Payload keys per Arya cURL: doc1_type, doc2_type, img1_base64, img2_base64, req_id.
     */
    public Map<String, Object> verifyFaceDirect(byte[] idFrontBytes, byte[] selfieBytes) throws IOException {
            String idB64 = toBase64(idFrontBytes);
            String selfieB64 = toBase64(selfieBytes);

            Map<String, Object> payload = Map.of(
                    "doc1_type", "image",
                    "doc2_type", "image",
                    "img1_base64", idB64,
                    "img2_base64", selfieB64,
                    "req_id", UUID.randomUUID().toString()
            );

        ClientResponse resp = aryaWebClient.post()
                .uri(props.getFaceVerifyPath())
                .header("token", props.getFaceApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .exchange()
                .block();

        if (resp == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "No response from Arya verifyFace");
        }

        HttpStatusCode status = resp.statusCode();
        String body = resp.bodyToMono(String.class).block();
        String contentType = resp.headers().contentType().map(MediaType::toString).orElse("");

        // Enhanced error logging
        System.out.println("Response Status: " + status.value());
        System.out.println("Response Content-Type: " + contentType);
        System.out.println("Response Body (first 500 chars): " + 
            (body != null ? body.substring(0, Math.min(500, body.length())) : "null"));

        // If HTTP error, surface provider message
        if (status.is4xxClientError() || status.is5xxServerError()) {
            String snippet = body == null ? "" : (body.length() > 300 ? body.substring(0, 300) + "..." : body);
            throw new ResponseStatusException(
                    status.is4xxClientError() ? HttpStatus.BAD_REQUEST : HttpStatus.BAD_GATEWAY,
                    "Arya verifyFace error (" + status.value() + "): " + snippet
            );
        }

        // If not JSON, don't try to parse
        if (contentType == null || !contentType.toLowerCase().contains("application/json")) {
            String snippet = body == null ? "" : (body.length() > 300 ? body.substring(0, 300) + "..." : body);
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Arya verifyFace returned non-JSON: " + snippet
            );
        }

        Map<String, Object> raw;
        try {
            raw = mapper.readValue(body, new TypeReference<>() {});
        } catch (JsonProcessingException jpe) {
            String snippet = body == null ? "" : (body.length() > 300 ? body.substring(0, 300) + "..." : body);
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Failed to parse Arya verifyFace JSON: " + snippet, jpe
            );
        }

        // Typical fields: 'similarity' or 'score', optionally under 'data'
        Double similarity = null;
        Object sim = raw.get("similarity");
        if (sim == null) sim = raw.get("score");
        if (sim == null && raw.get("data") instanceof Map<?,?> d) {
            Object s1 = d.get("similarity");
            Object s2 = d.get("score");
            sim = (s1 != null) ? s1 : s2;
        }
        if (sim != null) {
            try { similarity = Double.valueOf(sim.toString()); } catch (Exception ignored) {}
        }

        return Map.of(
                "similarity", similarity,
                "raw", raw
        );
    }

    /**
     * Convenience: given the stored front-ID bytes from step 1 and a fresh selfie,
     * extract the portrait and then compare to the selfie. Returns similarity and both raws.
     */
    public Map<String, Object> compareStoredFrontWithSelfie(byte[] storedFrontBytes, MultipartFile selfieWithId) throws IOException {
        Map<String, Object> cmp = verifyFaceDirect(storedFrontBytes, selfieWithId.getBytes());
        return Map.of(
                "similarity", cmp.get("similarity"),
                "compareRaw", cmp.get("raw")
        );
    }
}
