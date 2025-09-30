package com.logicnativesolution.servemeapi.service;

import com.logicnativesolution.servemeapi.config.JwtConfig;
import com.logicnativesolution.servemeapi.entities.User;
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

    public String generateAccessTokenFor(User user) {
        return generateTokenFor(user, jwtConfig.getAccessTokenExpiration());
    }

    public String generateRefreshTokenFor(User user) {
        return generateTokenFor(user, jwtConfig.getRefreshTokenExpiration());
    }

    private String generateTokenFor(User user, long expirationSeconds) {
        long expMs = System.currentTimeMillis() + (expirationSeconds * 1000L);

        return Jwts.builder()
                .subject(user.getId().toString()) // ✅ sub = UUID (what refresh expects)
                .claim("email", user.getEmail())
                .claim("firstName", user.getFirstName())
                .claim("lastName", user.getLastName())
                .issuedAt(new Date())
                .expiration(new Date(expMs))
                .signWith(Keys.hmacShaKeyFor(jwtConfig.getSecret().getBytes(StandardCharsets.UTF_8)))
                .compact();
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