package com.logicnativesolution.servemeapi.controller;

import com.logicnativesolution.servemeapi.config.CookieConfig;
import com.logicnativesolution.servemeapi.dto.*;
import com.logicnativesolution.servemeapi.dto.user.*;
import com.logicnativesolution.servemeapi.dto.verify.*;
import com.logicnativesolution.servemeapi.entities.Role;
import com.logicnativesolution.servemeapi.service.FirestoreService;
import com.logicnativesolution.servemeapi.util.ChannelUtils;
import com.logicnativesolution.servemeapi.entities.User;
import com.logicnativesolution.servemeapi.repository.RoleRepository;
import com.logicnativesolution.servemeapi.repository.UserRepository;
import com.logicnativesolution.servemeapi.service.EmailService;
import com.logicnativesolution.servemeapi.service.JwtService;
import com.logicnativesolution.servemeapi.service.RegisterUserService;
import com.logicnativesolution.servemeapi.util.CookieUtils;
import com.logicnativesolution.servemeapi.util.RegisterStatusUtils;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Slf4j
@Validated
@Getter
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth")
public class AuthenticationUserController {

    private final FirestoreService firestoreService;

    private static final String REFRESH_COOKIE_NAME = "refresh_token";
    private static final String REFRESH_COOKIE_PATH = "/api/auth/refresh";

    private final AuthenticationManager authenticationManager;
    private final RegisterUserService registerUserService;
    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final EmailService emailService;
    private final OtpCodesDto otpCodes;
    private final CurrentUser currentUser;
    private final ResetPasswordEmailDto resetPasswordEmail;
    private final CookieConfig cookieConfig;

    // -------------------------------------------------------------------------
    // Registration flow
    // -------------------------------------------------------------------------

    @PostMapping("/register")
    public ResponseEntity<StatusResponseDto> registerUserRequest(@Valid @RequestBody RegisterUsersDto request) {
        Role roleEntity = roleRepository.findByName(
                request.getRole() != null ? request.getRole().trim().toUpperCase() : null
        );

        if (roleEntity == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new StatusResponseDto(
                            RegisterStatusUtils.INVALID_ROLE.name(),
                            "Role not found.", null)
                    );
        }

        User user = registerUserService.registerUser(request);
        user.setRole(roleEntity);
        currentUser.setUser(user);

        return ResponseEntity.accepted().body(new StatusResponseDto(
                RegisterStatusUtils.OTP_REQUIRED.name(),
                "We sent verification codes to your phone/email.",
                currentUser.getUser()
        ));
    }

    // -------------------------------------------------------------------------
    // Username/password login → returns access token in body, sets refresh cookie
    // -------------------------------------------------------------------------

    @PostMapping("/login")
    public ResponseEntity<JwtTokenDto> loginUserRequest(
            @Valid @RequestBody LoginUsersDto request
    ) {
        // Authenticate credentials first
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword())
        );

        // Case-insensitive lookup
        User user = userRepository.findByEmailIgnoreCase(request.getEmail().trim())
                .orElseThrow();

        // sub = UUID (matches refresh flow)
        String accessToken = jwtService.generateAccessTokenFor(user);
        String refreshToken = jwtService.generateRefreshTokenFor(user);

        ResponseCookie rc = CookieUtils.buildRefreshCookie(cookieConfig, refreshToken);

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, rc.toString())
                .body(new JwtTokenDto(accessToken));
    }

    // -------------------------------------------------------------------------
    // Logout → expire the refresh cookie
    // -------------------------------------------------------------------------

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletResponse resp) {
        boolean secure = isSecureEnv();
        String sameSite = secure ? "Strict" : "Lax";

        ResponseCookie expired = ResponseCookie
                .from(REFRESH_COOKIE_NAME, "")
                .httpOnly(true)
                .secure(secure)
                .path(REFRESH_COOKIE_PATH)
                .sameSite(sameSite)
                .maxAge(0)
                .build();

        resp.addHeader(HttpHeaders.SET_COOKIE, expired.toString());
        return ResponseEntity.noContent().build();
    }

    // -------------------------------------------------------------------------
    // OTP handling (send/verify/update destination)
    // -------------------------------------------------------------------------

    @PostMapping("/otp/send")
    public ResponseEntity<Void> sendOtp(@Valid @RequestBody OtpSessionDto request) {
        if (ChannelUtils.SMS.name().equals(request.getChannel())) {
            otpCodes.setPhoneOtp(String.valueOf(emailService.generateEmailPhoneOtp()));
            log.info("SMS code generated: {}", otpCodes.getPhoneOtp());
        }

        if (ChannelUtils.EMAIL.name().equals(request.getChannel())) {
            otpCodes.setEmailOtp(String.valueOf(emailService.generateEmailPhoneOtp()));
            emailService.sendEmail(
                    request.getDestination(),
                    "ServeMe Email verification Code",
                    otpCodes.getEmailOtp()
            );
            log.info("Email code generated: {}", otpCodes.getEmailOtp());
        }

        return ResponseEntity.ok().build();
    }

    @PostMapping("/otp/verify")
    public ResponseEntity<?> verifyOtp(@Valid @RequestBody VerifyOtpDto request) {
        if (currentUser.getUser() == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new StatusResponseDto(
                            RegisterStatusUtils.NO_PENDING_REGISTRATION.name(),
                            "No user awaiting verification.",
                            null)
                    );
        }

        Map<String, Boolean> authorized = new HashMap<>();

        if (ChannelUtils.SMS.name().equals(request.getChannel())) {
            boolean isOk = request.getCode().equals(otpCodes.getPhoneOtp());
            otpCodes.setSmsVerified(isOk);
            authorized.put(ChannelUtils.SMS.name(), isOk);
        } else if (ChannelUtils.EMAIL.name().equals(request.getChannel())) {
            boolean isOk = request.getCode().equals(otpCodes.getEmailOtp());
            otpCodes.setEmailVerified(isOk);
            authorized.put(ChannelUtils.EMAIL.name(), isOk);
        }

        log.info("OTP verification result: {}", authorized);

        if (otpCodes.isSmsVerified() && otpCodes.isEmailVerified()) {
            return ResponseEntity.ok(new VerifiedResponseDto(
                    RegisterStatusUtils.VERIFIED.name(),
                    authorized,
                    "Both channels verified.",
                    null)
            );
        }

        return ResponseEntity.ok(new PendingResponseDto(
                RegisterStatusUtils.PENDING.name(),
                authorized)
        );
    }

    @PostMapping("/otp/update-destination")
    public ResponseEntity<?> updatePhoneEmailDestination(@Valid @RequestBody UpdateOtpChannelDto request) {
        if (currentUser.getUser() == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new StatusResponseDto(
                            RegisterStatusUtils.NO_PENDING_REGISTRATION.name(),
                            "No user awaiting verification.",
                            null));
        }

        if (ChannelUtils.SMS.name().equals(request.getChannel())) {
            currentUser.getUser().setPhoneNumber(request.getDestination());
        } else if (ChannelUtils.EMAIL.name().equals(request.getChannel())) {
            currentUser.getUser().setEmail(request.getDestination());
        }
        return ResponseEntity.ok().build();
    }

    // -------------------------------------------------------------------------
    // Persist verified user → set refresh cookie and return access token
    // -------------------------------------------------------------------------

    @PostMapping("/save-user")
    public ResponseEntity<?> persistUser(@Valid @RequestBody VerifiedUser request) {
        if (currentUser.getUser() == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new StatusResponseDto(
                            RegisterStatusUtils.NO_PENDING_REGISTRATION.name(),
                            "No user awaiting verification.",
                            null));
        }

        if (!"VERIFIED".equals(request.getStatus())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new StatusResponseDto(
                            RegisterStatusUtils.PENDING.name(),
                            "User not fully verified yet.",
                            null));
        }

        // Persist first
        User saved = userRepository.save(currentUser.getUser());
//        log.info("Persisted verified user {}", saved.getEmail());

        // Write users doc to Firestore (best-effort, non-fatal on error)
        try {
            Map<String, Object> userDoc = new HashMap<>();
            userDoc.put("role", saved.getRole() != null ? saved.getRole().getName() : null);
            userDoc.put("firstName", saved.getFirstName());
            userDoc.put("lastName", saved.getLastName());
            userDoc.put("email", saved.getEmail());
            userDoc.put("phone", saved.getPhoneNumber());
            userDoc.put("photoUrl", null);
            userDoc.put("createdAt", java.time.Instant.now().toString());
            userDoc.put("updatedAt", java.time.Instant.now().toString());
            firestoreService.set("users", saved.getId().toString(), userDoc);
        } catch (Exception ex) {
            log.warn("Failed to write users doc to Firestore", ex);
        }

        String accessToken  = jwtService.generateAccessTokenFor(saved);
        String refreshToken = jwtService.generateRefreshTokenFor(saved);

        // Clear OTP state
        otpCodes.setPhoneOtp(null);
        otpCodes.setEmailOtp(null);
        otpCodes.setSmsVerified(false);
        otpCodes.setEmailVerified(false);

        // Build cookie response (this creates Set-Cookie)
        ResponseEntity<JwtTokenDto> cookieResp = setRefreshCookieAndRespond(accessToken, refreshToken);

        // Map entity -> DTO (avoid lazy proxies in JSON)
        CreatedUserResponseDto body = getCreatedUserResponseDto(saved, cookieResp);

        ResponseCookie rc = CookieUtils.buildRefreshCookie(cookieConfig, refreshToken);

        // Forward Set-Cookie header
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .header(HttpHeaders.SET_COOKIE, rc.toString())   // <-- explicitly add it
                .body(body);
    }

    private static CreatedUserResponseDto getCreatedUserResponseDto(User saved, ResponseEntity<JwtTokenDto> cookieResp) {
        String roleName = (saved.getRole() != null ? saved.getRole().getName() : null);
        UserView view = new UserView(
                saved.getId(),
                saved.getFirstName(),
                saved.getLastName(),
                saved.getEmail(),
                roleName
        );

        return new CreatedUserResponseDto(
                Objects.requireNonNull(cookieResp.getBody()).getToken(),
                RegisterStatusUtils.VERIFIED.name(),
                true,
                (roleName != null ? roleName : "USER") + " Created Successfully: ",
                view
        );
    }

    // -------------------------------------------------------------------------
    // Refresh → reads httpOnly cookie, validates, returns new access token
    // (Keeps existing refresh cookie unless you implement rotation)
    // -------------------------------------------------------------------------

    @PostMapping("/refresh")
    public ResponseEntity<JwtTokenDto> refreshUserRequest(
            @CookieValue(value = "${serveme.cookie.name}", required = false) String refreshToken
    ) {
        log.info("Refreshing token: {}", refreshToken);
        if (refreshToken == null || refreshToken.isBlank()) {
            log.warn("Refresh attempt without cookie");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            if (!jwtService.validateToken(refreshToken)) {
                log.warn("Refresh token failed validation");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }

            String userId = jwtService.getUserIdFromToken(refreshToken);
            UUID uid;
            try { uid = UUID.fromString(userId); }
            catch (Exception e) {
                log.warn("Refresh token subject is not a UUID: {}", userId);
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }

            User user = userRepository.findById(uid).orElse(null);
            if (user == null) {
                log.warn("User not found for refresh subject: {}", uid);
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }

            String newAccess = jwtService.generateAccessTokenFor(user);
            // Reuse existing refresh cookie, don't rotate in dev:
            return setRefreshCookieAndRespond(newAccess, null);

        } catch (Exception ex) {
            log.warn("Refresh failed", ex);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
    }


    // -------------------------------------------------------------------------
    // Forgot password helpers
    // -------------------------------------------------------------------------

    @PostMapping("/forgot-password")
    public ResponseEntity<?> forgotPassword(@Valid @RequestBody ForgotPasswordDto forgotPassRequest) {
        registerUserService.resetPassword(resetPasswordEmail, forgotPassRequest);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/send-reset-password-code")
    public ResponseEntity<?> sendResetPasswordCodeRequest(@RequestBody ResetPasswordEmailDto resetPasswordEmailRequest) {
        resetPasswordEmail.setEmail(resetPasswordEmailRequest.getEmail());
        emailService.sendEmail(
                resetPasswordEmailRequest.getEmail(),
                "ServeMe Reset Password Otp Code 🔐",
                String.valueOf(emailService.generateEmailPhoneOtp())
        );
        return ResponseEntity.ok().build();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private ResponseEntity<JwtTokenDto> setRefreshCookieAndRespond(
            String newAccessToken,
            String refreshTokenOrNull // when null, reuse the existing cookie; when "", clear
    ) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok();

        if (refreshTokenOrNull != null) {
            ResponseCookie rc = CookieUtils.buildRefreshCookie(cookieConfig, refreshTokenOrNull);
            log.info("SET-COOKIE -> {}", rc);
            builder.header(HttpHeaders.SET_COOKIE, rc.toString());
        }

        JwtTokenDto body = new JwtTokenDto(newAccessToken);
        return builder.body(body);
    }

    /**
     * Determines whether refresh cookies should be marked Secure/Strict.
     * Controlled by `serveme.cookies.secure` in application.yaml
     */
    private boolean isSecureEnv() {
        return cookieConfig.isSecure();
    }
}