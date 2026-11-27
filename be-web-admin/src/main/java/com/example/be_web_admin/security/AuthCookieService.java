package com.example.be_web_admin.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** Refresh tokens for the browser are stored only in an HttpOnly, SameSite cookie. */
@Component
public class AuthCookieService {

    public static final String REFRESH_COOKIE = "ADMIN_REFRESH";
    private static final String COOKIE_PATH = "/api/web/admin/auth";
    private final boolean secure;
    private final Set<String> allowedOrigins;

    public AuthCookieService(@Value("${app.security.cookie-secure:false}") boolean secure,
                             @Value("${app.security.allowed-origins}") String configuredOrigins) {
        this.secure = secure;
        this.allowedOrigins = Arrays.stream(configuredOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean isAllowedOrigin(String origin) {
        return origin != null && allowedOrigins.contains(origin);
    }

    public ResponseCookie refreshCookie(String token) {
        return ResponseCookie.from(REFRESH_COOKIE, token)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Strict")
                .path(COOKIE_PATH)
                .maxAge(Duration.ofDays(30))
                .build();
    }

    public ResponseCookie clearRefreshCookie() {
        return ResponseCookie.from(REFRESH_COOKIE, "")
                .httpOnly(true)
                .secure(secure)
                .sameSite("Strict")
                .path(COOKIE_PATH)
                .maxAge(Duration.ZERO)
                .build();
    }
}
