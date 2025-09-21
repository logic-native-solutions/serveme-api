package com.logicnativesolution.servemeapi.service;

import com.logicnativesolution.servemeapi.config.JwtConfig;
import com.logicnativesolution.servemeapi.repository.UserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Date;

@RequiredArgsConstructor
@Service
public class JwtService {
    private final JwtConfig jwtConfig;
    private final UserRepository userRepository;

    public String generateAccessToken(String email) {
        return generateToken(email, jwtConfig.getAccessTokenExpiration()); // seconds
    }

    public String generateRefreshToken(String email) {
        return generateToken(email, jwtConfig.getRefreshTokenExpiration()); // seconds
    }

    private String generateToken(String email, long expirationSeconds) {
        var user = userRepository.findByEmail(email).orElseThrow();

        long expMs = System.currentTimeMillis() + (expirationSeconds * 1000L);

        return Jwts.builder()
                .subject(String.valueOf(user.getId()))       // subject = userId
                .claim("email", user.getEmail())             // email claim
                .claim("firstName", user.getFirstName())
                .claim("lastName", user.getLastName())
                .issuedAt(new Date())
                .expiration(new Date(expMs))
                .signWith(Keys.hmacShaKeyFor(jwtConfig.getSecret().getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    public boolean isTokenValid(String token) {
        return validateToken(token);
    }

    public boolean validateToken(String token) {
        try {
            return !getClaims(token).getExpiration().before(new Date());
        } catch (JwtException e) {
            return false;
        }
    }

    private Claims getClaims(String token) {
        return Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(jwtConfig.getSecret().getBytes(StandardCharsets.UTF_8)))
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public String getUserIdFromToken(String token) {
        return getClaims(token).getSubject();
    }

    public String getEmailFromToken(String token) {
        return getClaims(token).get("email", String.class);
    }
}