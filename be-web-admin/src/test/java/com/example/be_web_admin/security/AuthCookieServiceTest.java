package com.example.be_web_admin.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AuthCookieServiceTest {

    private final AuthCookieService service = new AuthCookieService(
            true, "https://admin.example.test, http://localhost:5173");

    @Test
    void refreshCookieIsHttpOnlySecureStrictAndScopedToAuthRoutes() {
        var cookie = service.refreshCookie("refresh-token");

        assertEquals(AuthCookieService.REFRESH_COOKIE, cookie.getName());
        assertEquals("refresh-token", cookie.getValue());
        assertTrue(cookie.isHttpOnly());
        assertTrue(cookie.isSecure());
        assertEquals("Strict", cookie.getSameSite());
        assertEquals("/api/web/admin/auth", cookie.getPath());
        assertEquals(30L * 24 * 60 * 60, cookie.getMaxAge().getSeconds());
    }

    @Test
    void onlyConfiguredExactOriginsAreAccepted() {
        assertTrue(service.isAllowedOrigin("https://admin.example.test"));
        assertTrue(service.isAllowedOrigin("http://localhost:5173"));
        assertFalse(service.isAllowedOrigin("https://admin.example.test/"));
        assertFalse(service.isAllowedOrigin("https://attacker.example"));
        assertFalse(service.isAllowedOrigin(null));
    }

    @Test
    void clearCookieUsesSameScopeAndExpiresImmediately() {
        var cookie = service.clearRefreshCookie();

        assertEquals(AuthCookieService.REFRESH_COOKIE, cookie.getName());
        assertEquals("/api/web/admin/auth", cookie.getPath());
        assertTrue(cookie.isHttpOnly());
        assertTrue(cookie.isSecure());
        assertEquals("Strict", cookie.getSameSite());
        assertEquals(0, cookie.getMaxAge().getSeconds());
    }
}
