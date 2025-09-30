package com.logicnativesolution.servemeapi.util;

import com.logicnativesolution.servemeapi.config.CookieConfig;
import org.springframework.http.ResponseCookie;

public final class CookieUtils {
    private CookieUtils() {}

    public static ResponseCookie buildRefreshCookie(CookieConfig cfg, String token) {
        ResponseCookie.ResponseCookieBuilder b = ResponseCookie
                .from(cfg.getName(), token == null ? "" : token)
                .httpOnly(cfg.isHttpOnly())
                .secure(cfg.isSecure())
                .path(cfg.getPath())
                .maxAge(token == null ? 0 : cfg.getMaxAgeSeconds());
        // Don’t set Domain in dev; host-only cookie is safer.
        if (cfg.getSameSite() != null) {
            b = b.sameSite(cfg.getSameSite());
        }
        return b.build();
    }
}
