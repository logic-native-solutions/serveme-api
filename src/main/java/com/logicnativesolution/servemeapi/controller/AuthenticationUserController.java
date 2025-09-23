package com.logicnativesolution.servemeapi.controller;

import com.logicnativesolution.servemeapi.config.JwtConfig;
import com.logicnativesolution.servemeapi.config.CookieConfig;
import com.logicnativesolution.servemeapi.defaults.ChannelEnum;
import com.logicnativesolution.servemeapi.dto.*;
import com.logicnativesolution.servemeapi.repository.UserRepository;
import com.logicnativesolution.servemeapi.service.AryaRsaIdService;
import com.logicnativesolution.servemeapi.service.EmailService;
import com.logicnativesolution.servemeapi.service.JwtService;
import com.logicnativesolution.servemeapi.service.RegisterUserService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth")
public class AuthenticationUserController {

    private static final String REFRESH_COOKIE_NAME = "refresh_token";
    private static final String REFRESH_COOKIE_PATH = "/api/auth/refresh";

    private final AuthenticationManager authenticationManager;
    private final RegisterUserService registerUserService;
    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final JwtConfig jwtConfig;
    private final EmailService emailService;
    private final OtpCodesDto otpCodes;
    private final CurrentUser currentUser;
    private final ResetPasswordEmailDto resetPasswordEmail;
    private final AryaRsaIdService aryaRsaIdService;
    private final CookieConfig cookieConfig;

    @PostMapping("/register")
    public ResponseEntity<SimpleResponseDto> registerUserRequest(@Valid @RequestBody RegisterUsersDto request) {
        var user = registerUserService.registerUser(request);
        currentUser.setUser(user);
        log.info("Registered pending user {}. Awaiting OTP verification.", user.getEmail());

        return ResponseEntity.accepted().body(new SimpleResponseDto(
                "OTP_REQUIRED",
                "We sent verification codes to your phone/email.",
                currentUser.getUser()
        ));
    }

    @PostMapping("/login")
    public ResponseEntity<JwtTokenDto> loginUserRequest(
            @Valid @RequestBody LoginUsersDto request,
            HttpServletResponse response
    ) {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword())
        );

        var accessToken = jwtService.generateAccessToken(request.getEmail());
        var refreshToken = jwtService.generateRefreshToken(request.getEmail());

        return setRefreshCookieAndRespond(accessToken, refreshToken, response, HttpStatus.OK);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletResponse resp) {
        boolean secure = isSecureEnv();
        String sameSite = secure ? "Strict" : "Lax";

        var expired = ResponseCookie
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

    @PostMapping("/otp/send")
    public ResponseEntity<Void> sendOtp(@Valid @RequestBody OtpSessionDto request) {
        if (ChannelEnum.SMS.name().equals(request.getChannel())) {
            otpCodes.setPhoneOtp(String.valueOf(emailService.generateEmailPhoneOtp()));
            System.out.printf("\nSMS code generated: %s", otpCodes.getPhoneOtp());
        }

        if (ChannelEnum.EMAIL.name().equals(request.getChannel())) {
            otpCodes.setEmailOtp(String.valueOf(emailService.generateEmailPhoneOtp()));
            emailService.sendEmail(
                    request.getDestination(),
                    "ServeMe Email verification Code",
                    otpCodes.getEmailOtp()
            );
            System.out.printf("\nEmail code generated: %s", otpCodes.getEmailOtp());
        }

        return ResponseEntity.ok().build();
    }

    @PostMapping("/otp/verify")
    public ResponseEntity<?> verifyOtp(@Valid @RequestBody VerifyOtpDto request) {
        if (currentUser.getUser() == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new SimpleResponseDto("NO_PENDING_REGISTRATION",
                            "No user awaiting verification.", null));
        }

        Map<String, Boolean> authorized = new HashMap<>();

        if (ChannelEnum.SMS.name().equals(request.getChannel())) {
            boolean isOk = request.getCode().equals(otpCodes.getPhoneOtp());
            otpCodes.setSmsVerified(isOk);
            authorized.put(ChannelEnum.SMS.name(), isOk);
        } else if (ChannelEnum.EMAIL.name().equals(request.getChannel())) {
            boolean isOk = request.getCode().equals(otpCodes.getEmailOtp());
            otpCodes.setEmailVerified(isOk);
            authorized.put(ChannelEnum.EMAIL.name(), isOk);
        }

        if (otpCodes.isSmsVerified() && otpCodes.isEmailVerified()) {
            return ResponseEntity.ok(new SimpleResponseDto("VERIFIED",
                    "Both channels verified.", null));
        }

        return ResponseEntity.ok(new PendingResponseDto("PENDING", authorized));
    }

    @PostMapping("/save-user")
    public ResponseEntity<?> persistUser(
            @Valid @RequestBody VerifiedUser request,
            HttpServletResponse response
    ) {
        if (currentUser.getUser() == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new SimpleResponseDto("NO_PENDING_REGISTRATION",
                            "No user awaiting verification.", null));
        }

        if (!"VERIFIED".equals(request.getStatus())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new SimpleResponseDto("PENDING",
                            "User not fully verified yet.", null));
        }

        // Persist pending user BEFORE generating tokens if JwtService queries DB
        var saved = userRepository.save(currentUser.getUser());
        log.info("Persisted verified user {}", saved.getEmail());

        var accessToken = jwtService.generateAccessToken(saved.getEmail());
        var refreshToken = jwtService.generateRefreshToken(saved.getEmail());

        // Clear OTP state
        otpCodes.setPhoneOtp(null);
        otpCodes.setEmailOtp(null);
        otpCodes.setSmsVerified(false);
        otpCodes.setEmailVerified(false);

        return setRefreshCookieAndRespond(accessToken, refreshToken, response, HttpStatus.CREATED);
    }

    @PostMapping("/otp/update-destination")
    public ResponseEntity<?> updatePhoneEmailDestination(
            @Valid @RequestBody UpdateOtpChannelDto request
    ) {
        if (ChannelEnum.SMS.name().equals(request.getChannel())) {
            currentUser.getUser().setPhoneNumber(request.getDestination());
        } else if (ChannelEnum.EMAIL.name().equals(request.getChannel())) {
            currentUser.getUser().setEmail(request.getDestination());
        }
        return ResponseEntity.ok().build();
    }

    @PostMapping("/refresh")
    public ResponseEntity<JwtTokenDto> refreshUserRequest(
            @CookieValue(value = REFRESH_COOKIE_NAME, required = false) String refreshToken
    ) {
        if (refreshToken == null || refreshToken.isBlank()) {
            log.warn("Refresh attempt without cookie");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            if (!jwtService.validateToken(refreshToken)) {
                log.warn("Refresh token failed validation");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }

            var userId = jwtService.getUserIdFromToken(refreshToken);
            UUID uid;
            try {
                uid = UUID.fromString(userId);
            } catch (Exception parseEx) {
                log.warn("Refresh token subject is not a UUID: {}", userId);
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }

            var user = userRepository.findById(uid).orElse(null);
            if (user == null) {
                log.warn("User not found for refresh subject: {}", uid);
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }

            var accessToken = jwtService.generateAccessToken(user.getEmail());
            return setRefreshCookieAndRespond(accessToken, refreshToken, null, HttpStatus.OK);
        } catch (Exception ex) {
            log.warn("Refresh failed", ex);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
    }

    private ResponseEntity<JwtTokenDto> setRefreshCookieAndRespond(
            String accessToken,
            String refreshToken,
            HttpServletResponse response,
            HttpStatus status
    ) {
        boolean secure = isSecureEnv();
        String sameSite = secure ? "Strict" : "Lax";

        if (refreshToken != null && response != null) {
            var cookie = ResponseCookie.from(REFRESH_COOKIE_NAME, refreshToken)
                    .httpOnly(true)
                    .secure(secure)
                    .path(REFRESH_COOKIE_PATH)
                    .sameSite(sameSite)
                    .maxAge(jwtConfig.getRefreshTokenExpiration())
                    .build();
            response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        }
        return ResponseEntity.status(status).body(new JwtTokenDto(accessToken));
    }

    /**
     * Determines whether refresh cookies should be marked Secure/Strict.
     * Controlled by `serveme.cookies.secure` in application.yaml
     */
    private boolean isSecureEnv() {
        return cookieConfig.isSecure();
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<?> forgotPassword(@Valid @RequestBody ForgotPasswordDto forgotPassRequest) {
        registerUserService.resetPassword(resetPasswordEmail,forgotPassRequest);
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
        return  ResponseEntity.ok().build();
    }
}
