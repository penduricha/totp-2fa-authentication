package com.example.be_web_admin.security.jwt;

import com.example.be_web_admin.security.jwt.user.AdminJwtTokenProvider;
import com.example.be_web_admin.service.redis.AdminTokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Central bearer-token authentication for all non-public API routes. */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final AdminJwtTokenProvider tokenProvider;
    private final AdminTokenService tokenService;

    public JwtAuthenticationFilter(AdminJwtTokenProvider tokenProvider, AdminTokenService tokenService) {
        this.tokenProvider = tokenProvider;
        this.tokenService = tokenService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;
        String path = request.getServletPath();
        if ("POST".equalsIgnoreCase(request.getMethod())) {
            return "/web/admin/auth/login".equals(path)
                    || "/web/admin/auth/login/totp".equals(path)
                    || "/mobile/admin/auth/login".equals(path);
        }
        // Refresh tokens are validated and atomically rotated by the refresh endpoint itself.
        return "GET".equalsIgnoreCase(request.getMethod())
                && ("/web/admin/auth/login/auto/refresh-token".equals(path)
                || "/mobile/admin/auth/login/auto/refresh-token".equals(path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authorization.substring(7).trim();
        final Long adminId;
        try {
            if (token.isEmpty()
                    || !tokenProvider.validateAccessToken(token)
                    || !tokenService.findAdminAccessTokenByJwtToken(token)) {
                reject(response);
                return;
            }
            adminId = tokenProvider.getAdminIdFromToken(token);
        } catch (RuntimeException e) {
            reject(response);
            return;
        }

        var authentication = new UsernamePasswordAuthenticationToken(
                String.valueOf(adminId), null, AuthorityUtils.createAuthorityList("ROLE_ADMIN"));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        filterChain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response) throws IOException {
        SecurityContextHolder.clearContext();
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.getWriter().write("{\"success\":false,\"message\":\"Unauthorized\"}");
    }
}
