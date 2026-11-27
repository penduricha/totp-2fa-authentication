package com.example.be_web_admin.security.jwt.user;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

/**
 * Creates and validates HS256 access/refresh JWTs.
 * The signing key is supplied out of band as Base64-encoded random bytes; it is never
 * generated from a source-code default, because that would make every installation share it.
 */
@Component
public class AdminJwtTokenProvider {

    private static final long ACCESS_TOKEN_EXPIRATION = 5 * 60 * 1000L;
    private static final long REFRESH_TOKEN_EXPIRATION = 30L * 24 * 60 * 60 * 1000L;
    private static final String ACCESS = "ACCESS";
    private static final String REFRESH = "REFRESH";

    private final SecretKey signingKey;

    public AdminJwtTokenProvider(@Value("${app.jwt.secret-base64}") String encodedSecret) {
        final byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(encodedSecret);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("app.jwt.secret-base64 must be valid Base64", e);
        }
        if (keyBytes.length < 32) {
            throw new IllegalStateException("JWT HS256 signing key must contain at least 32 random bytes");
        }
        this.signingKey = new SecretKeySpec(keyBytes, "HmacSHA256");
    }

    public String generateAccessToken(Long adminId) {
        return generateToken(adminId, ACCESS, ACCESS_TOKEN_EXPIRATION);
    }

    public String generateRefreshToken(Long adminId) {
        return generateToken(adminId, REFRESH, REFRESH_TOKEN_EXPIRATION);
    }

    private String generateToken(Long adminId, String tokenType, long lifetimeMillis) {
        Date now = new Date();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(String.valueOf(adminId))
                .issuedAt(now)
                .expiration(new Date(now.getTime() + lifetimeMillis))
                .issuer("totp-2fa-authentication")
                .claim("token_type", tokenType)
                .signWith(signingKey)
                .compact();
    }

    public Long getAdminIdFromToken(String token) {
        return Long.parseLong(parseClaims(token).getSubject());
    }

    public String getTokenType(String token) {
        return parseClaims(token).get("token_type", String.class);
    }

    public boolean validateToken(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException | NullPointerException e) {
            return false;
        }
    }

    public boolean validateToken(String token, String expectedType) {
        try {
            Claims claims = parseClaims(token);
            return expectedType.equals(claims.get("token_type", String.class))
                    && claims.getSubject() != null
                    && claims.getIssuer().equals("totp-2fa-authentication");
        } catch (JwtException | IllegalArgumentException | NullPointerException e) {
            return false;
        }
    }

    public boolean validateAccessToken(String token) {
        return validateToken(token, ACCESS);
    }

    public boolean validateRefreshToken(String token) {
        return validateToken(token, REFRESH);
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .requireIssuer("totp-2fa-authentication")
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
