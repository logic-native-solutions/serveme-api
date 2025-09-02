package com.logicnativesolution.servemeapi.controller;

import com.logicnativesolution.servemeapi.config.JwtConfig;
import com.logicnativesolution.servemeapi.dto.JwtTokenDto;
import com.logicnativesolution.servemeapi.dto.LoginUsersDto;
import com.logicnativesolution.servemeapi.repository.UserRepository;
import com.logicnativesolution.servemeapi.service.JwtService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@AllArgsConstructor
@RestController
@RequestMapping("/api/auth")
public class AuthenticationUserController {
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final JwtConfig jwtConfig;

    @PostMapping("/login")
    public ResponseEntity<JwtTokenDto> loginUserRequest(
            @Valid @RequestBody LoginUsersDto request,
            HttpServletResponse response
    ) {
        authenticationManager.authenticate(
          new UsernamePasswordAuthenticationToken(
           request.getEmail(),
           request.getPassword())
        );

        var user = userRepository.findByEmail(request.getEmail()).orElseThrow();
        var accessToken = jwtService.generateAccessToken(user.getEmail());
        var refreshToken = jwtService.generateRefreshToken(user.getEmail());
        var cookie = new Cookie("refresh_token", refreshToken);

        cookie.setHttpOnly(true);
        cookie.setPath("/api/auth/refresh");
        cookie.setMaxAge(jwtConfig.getRefreshTokenExpiration());
        cookie.setSecure(true);
        response.addCookie(cookie);

        return ResponseEntity.ok(new JwtTokenDto(accessToken));
    }

    @PostMapping("/refresh")
    public ResponseEntity<JwtTokenDto> refreshUserRequest(
            @CookieValue("refresh_token") String refreshToken
    ) {
        if(!jwtService.validateToken(refreshToken)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        var userId = jwtService.getUserIdFromToken(refreshToken);
        var user = userRepository.findById(UUID.fromString(userId)).orElseThrow();
        var accessToken = jwtService.generateAccessToken(user.getEmail());

        return ResponseEntity.ok(new JwtTokenDto(accessToken));
    }
}
