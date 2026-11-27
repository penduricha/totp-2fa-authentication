package com.example.be_web_admin.security.jwt.user;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminJwtTokenProviderTest {

    private final AdminJwtTokenProvider provider = new AdminJwtTokenProvider(
            Base64.getEncoder().encodeToString(new byte[32]));

    @Test
    void accessAndRefreshTokensAreBoundToTheirPurpose() {
        String access = provider.generateAccessToken(1775L);
        String refresh = provider.generateRefreshToken(1775L);

        assertTrue(provider.validateAccessToken(access));
        assertFalse(provider.validateRefreshToken(access));
        assertTrue(provider.validateRefreshToken(refresh));
        assertFalse(provider.validateAccessToken(refresh));
        assertEquals(1775L, provider.getAdminIdFromToken(access));
    }

    @Test
    void rejectsShortSigningKeysAtStartup() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        assertThrows(IllegalStateException.class, () -> new AdminJwtTokenProvider(shortKey));
    }

    @Test
    void rejectsTamperedToken() {
        String token = provider.generateAccessToken(1775L);
        String tampered = token.substring(0, token.length() - 2) + "aa";
        assertFalse(provider.validateAccessToken(tampered));
    }
}
