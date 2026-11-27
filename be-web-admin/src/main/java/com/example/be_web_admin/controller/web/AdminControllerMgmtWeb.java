package com.example.be_web_admin.controller.web;

import com.example.be_web_admin.dto.AdminLoginDTO;
import com.example.be_web_admin.dto.LoginOutcome;
import com.example.be_web_admin.security.AuthCookieService;
import com.example.be_web_admin.security.jwt.user.AdminJwtTokenProvider;
import com.example.be_web_admin.service.jpa.impl.AdminLoginServiceImpl;
import com.example.be_web_admin.service.redis.impl.AdminTokenServiceImpl;
import com.example.be_web_admin.service.jpa.impl.AdminServiceImpl;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/web/admin")
public class AdminControllerMgmtWeb {

    private final AdminServiceImpl adminService;

    private final AdminTokenServiceImpl adminTokenService;

    private final AdminLoginServiceImpl adminLoginService;
    private final AuthCookieService authCookieService;

    @Autowired
    private AdminJwtTokenProvider adminJwtTokenProvider;


    public AdminControllerMgmtWeb(AdminServiceImpl adminService, AdminTokenServiceImpl adminTokenService,
                                  AdminLoginServiceImpl adminLoginService, AuthCookieService authCookieService) {
        this.adminService = adminService;
        this.adminTokenService = adminTokenService;
        this.adminLoginService = adminLoginService;
        this.authCookieService = authCookieService;
    }

    //    {
    //  "adminId": 2500021772,
    //  "password": "1234"
    //}

    //Login successfully
    //        {
    //            "success": true,
    //                "message": "Đăng nhập thành công",
    //                "data": {
    //            "adminId": 2500021772,
    //                    "jwtToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
    //        }
    //        }

    //Login fail
    //    {
    //        "success": false,
    //            "message": "Mã sinh viên hoặc mật khẩu không chính xác",
    //            "data": null
    //    }
//    @PostMapping("/auth/login")
//    public ResponseEntity<?> loginAdmin(@RequestBody Map<String, Object> adminRequestLogin) throws RuntimeException {
//        Number adminIdInt = (Number) adminRequestLogin.get("adminId");
//        Long adminId = adminIdInt != null ? adminIdInt.longValue() : null;
//        String password = (String) adminRequestLogin.get("password");
//
//        AdminLoginDTO loginRequestAdminDTO = new AdminLoginDTO();
//        loginRequestAdminDTO.setAdminId(adminId);
//        loginRequestAdminDTO.setPassword(password);
//
//        // Gọi service trả về kết quả (ví dụ trả về Map, Token, hoặc DTO)
//        Object result = adminService.loginAdmin(
//                loginRequestAdminDTO.getAdminId(),
//                loginRequestAdminDTO.getPassword()
//        );
//
//        return ResponseEntity.ok(result);
//    }

//    @PostMapping("/auth/login/mobile")
//    public ResponseEntity<?> loginAdmin(@RequestBody Map<String, Object> adminRequestLogin) throws RuntimeException {
//
//    }

    //    @PostMapping("/auth/logout/mobile")
//    public ResponseEntity<?> loginAdmin(@RequestBody Map<String, Object> adminRequestLogin) throws RuntimeException {
//
//    }


    @PostMapping("/auth/logout")
    public ResponseEntity<?> logoutAdmin(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @CookieValue(name = AuthCookieService.REFRESH_COOKIE, required = false) String refreshToken,
            @RequestHeader(value = "Origin", required = false) String origin,
            Authentication authentication) {
        String accessToken = bearerToken(authorization);
        Long authenticatedAdminId = Long.valueOf(authentication.getName());

        // Cookie-authenticated state changes require a same-origin trusted browser request.
        if (!authCookieService.isAllowedOrigin(origin)
                || accessToken == null || refreshToken == null
                || !adminJwtTokenProvider.validateRefreshToken(refreshToken)
                || !authenticatedAdminId.equals(adminJwtTokenProvider.getAdminIdFromToken(refreshToken))
                || !adminTokenService.findAdminRefreshTokenByJwtToken(refreshToken)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .header(HttpHeaders.SET_COOKIE, authCookieService.clearRefreshCookie().toString())
                    .body(Map.of("success", false, "message", "Invalid session tokens."));
        }

        adminTokenService.removeAdminAccessTokenByJwtToken(accessToken);
        adminTokenService.removeAdminRefreshTokenByJwtToken(refreshToken);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, authCookieService.clearRefreshCookie().toString())
                .body(Map.of("status", HttpStatus.OK.value(), "message", "Log out successfully."));
    }

    private String bearerToken(String authorization) {
        return authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7).trim() : null;
    }

    @GetMapping("/auth/login/auto/access-token")
    public ResponseEntity<?> loginAdminAutoBySendAccessToken(
            @RequestHeader(value = "Authorization", required = false) String authHeader) throws RuntimeException {

        // 1. Kiểm tra sự tồn tại & định dạng của Header Authorization
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            Map<String, Object> responseError = new HashMap<>();
            responseError.put("success", false);
            responseError.put("message", "Wrong format Header Authorization (Bearer <token>).");
            responseError.put("data", null);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(responseError);
        }

        // 2. Lấy Token ra khỏi chuỗi "Bearer "
        String jwtToken = authHeader.substring(7);

        // 3. Verify chữ ký & hạn sử dụng JWT Token
        if (!adminJwtTokenProvider.validateAccessToken(jwtToken)) {
            Map<String, Object> responseInvalid = new HashMap<>();
            responseInvalid.put("success", false);
            responseInvalid.put("message", "Token is invalid or expired.");
            responseInvalid.put("data", null);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(responseInvalid);
        }

        // 4. Kiểm tra sự tồn tại của Access Token trong Redis DB
        boolean isTokenExist = adminTokenService.findAdminAccessTokenByJwtToken(jwtToken);
        if (!isTokenExist) {
            Map<String, Object> responseNotFound = new HashMap<>();
            responseNotFound.put("success", false);
            responseNotFound.put("message", "Token is unexisted or revoked in Redis.");
            responseNotFound.put("data", null);
            // Trả về 401 Unauthorized khi token không tìm thấy trong Redis
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(responseNotFound);
        }

        // 5. Trích xuất adminId từ Token hợp lệ
        Long adminId = adminJwtTokenProvider.getAdminIdFromToken(jwtToken);

        // 6. Trả về Response thành công (HTTP 200 OK)
        Map<String, Object> responseSuccess = new HashMap<>();
        responseSuccess.put("success", true);
        responseSuccess.put("message", "Auto login successfully.");
        responseSuccess.put("data", Map.of("adminId", adminId));

        return ResponseEntity.ok(responseSuccess);
    }

    @PostMapping("/auth/login/auto/refresh-token")
    public ResponseEntity<?> loginAdminAutoBySendRefreshToken(
            @CookieValue(name = AuthCookieService.REFRESH_COOKIE, required = false) String refreshToken,
            @RequestHeader(value = "Origin", required = false) String origin) {
        if (!authCookieService.isAllowedOrigin(origin)
                || refreshToken == null
                || !adminJwtTokenProvider.validateRefreshToken(refreshToken)
                || !adminTokenService.findAdminRefreshTokenByJwtToken(refreshToken)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .header(HttpHeaders.SET_COOKIE, authCookieService.clearRefreshCookie().toString())
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .body(Map.of("success", false, "message", "Refresh session is invalid or expired."));
        }

        Long adminId = adminJwtTokenProvider.getAdminIdFromToken(refreshToken);
        if (!adminTokenService.removeAdminRefreshTokenByJwtToken(refreshToken)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .header(HttpHeaders.SET_COOKIE, authCookieService.clearRefreshCookie().toString())
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .body(Map.of("success", false, "message", "Refresh session was already consumed."));
        }

        Map<String, Object> tokenNew = adminTokenService.createTokenAdmin(adminId);
        if (tokenNew == null || tokenNew.isEmpty()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header(HttpHeaders.SET_COOKIE, authCookieService.clearRefreshCookie().toString())
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .body(Map.of("success", false, "message", "Could not renew the session."));
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE,
                        authCookieService.refreshCookie(String.valueOf(tokenNew.get("refreshToken"))).toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(Map.of("success", true, "message", "Auto login successfully.",
                        "data", Map.of("adminId", adminId, "accessToken", tokenNew.get("accessToken"))));
    }

    @GetMapping ("/information/basic")
    //@RequestHeader(value = "Authorization", required = false) String authHeader
    public ResponseEntity<?> getAdminInformationBasic(@RequestParam Long adminId, Authentication authentication) throws RuntimeException {
        if (!isOwnAccount(adminId, authentication)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("success", false, "message", "Forbidden"));
        }
        Map<String, Object> responseHttp = new HashMap<>();
        Map<String, Object> responseData = adminService.getAdminInfo(adminId);

        responseHttp.put("status", HttpStatus.OK.value());

        if(responseData.isEmpty()) {
            responseHttp.put("message", "Admin not found.");
        } else {
            responseHttp.put("message", "Admin found successfully.");
        }
        responseHttp.put("data", responseData);
        return ResponseEntity.status(HttpStatus.OK).body(responseHttp);
    }

    @GetMapping ("/information/detail")
    //@RequestHeader(value = "Authorization", required = false) String authHeader
    public ResponseEntity<?> getAdminInformationDetail(@RequestParam Long adminId, Authentication authentication) throws RuntimeException {
        if (!isOwnAccount(adminId, authentication)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("success", false, "message", "Forbidden"));
        }
        Map<String, Object> responseHttp = new HashMap<>();
        Map<String, Object> responseData = adminService.getAdminDetailInformation(adminId);

        responseHttp.put("status", HttpStatus.OK.value());

        if(responseData.isEmpty()) {
            responseHttp.put("message", "Admin not found.");
        } else {
            responseHttp.put("message", "Admin found successfully.");
        }
        responseHttp.put("data", responseData);
        return ResponseEntity.status(HttpStatus.OK).body(responseHttp);
    }

//    @PostMapping("/login")   // giữ đường dẫn hiện tại: Vue gọi /admin/auth/login
//    public ResponseEntity<?> login(@RequestBody AdminLoginDTO req, HttpServletRequest http) {
//        LoginOutcome r = adminLoginService.login(req, http.getRemoteAddr(), http.getHeader("User-Agent"));
//        return switch (r.status()) {
//            case OK -> ResponseEntity.ok(Map.of("success", true,
//                    "data", Map.of("accessToken", r.accessToken(), "refreshToken", r.refreshToken())));
//            case TOTP_REQUIRED, TOTP_INVALID -> ResponseEntity.ok(Map.of("success", false,
//                    "data", Map.of("code", r.status().name())));
//            case LOCKED -> ResponseEntity.ok(Map.of("success", false,
//                    "data", Map.of("code", "LOCKED", "retryAfterSeconds", r.retryAfterSeconds())));
//            default -> ResponseEntity.ok(Map.of("success", false));   // BAD_CREDENTIALS: giữ như cũ
//        };
//    }

    // Web login is a two-step protocol: password -> short-lived Redis challenge -> TOTP.
    @PostMapping("/auth/login")
    public ResponseEntity<?> loginAdmin(@RequestBody AdminLoginDTO req, HttpServletRequest http) {
        LoginOutcome outcome = adminLoginService.startWebLogin(req, http.getRemoteAddr());
        return loginResponse(outcome, req.getAdminId());
    }

    @PostMapping("/auth/login/totp")
    public ResponseEntity<?> completeTotpLogin(@RequestBody AdminLoginDTO req, HttpServletRequest http) {
        LoginOutcome outcome = adminLoginService.completeWebLogin(
                req.getChallengeId(), req.getTotpCode(), http.getRemoteAddr(), http.getHeader("User-Agent"));
        Long adminId = outcome.status() == com.example.be_web_admin.enums.LoginStatus.OK
                ? adminJwtTokenProvider.getAdminIdFromToken(outcome.accessToken()) : null;
        return loginResponse(outcome, adminId);
    }

    private boolean isOwnAccount(Long requestedAdminId, Authentication authentication) {
        return authentication != null && requestedAdminId != null
                && requestedAdminId.toString().equals(authentication.getName());
    }

    private ResponseEntity<?> loginResponse(LoginOutcome outcome, Long adminId) {
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.PRAGMA, "no-cache");
        if (outcome.status() == com.example.be_web_admin.enums.LoginStatus.OK) {
            response.header(HttpHeaders.SET_COOKIE, authCookieService.refreshCookie(outcome.refreshToken()).toString());
        }
        return response.body(buildLoginResponse(outcome, adminId));
    }

    private Map<String, Object> buildLoginResponse(LoginOutcome outcome, Long adminId) {
        Map<String, Object> body = new HashMap<>();
        switch (outcome.status()) {
            case OK -> {
                body.put("success", true);
                body.put("message", "Đăng nhập thành công");
                body.put("data", Map.of(
                        "adminId", adminId,
                        "accessToken", outcome.accessToken()));
            }
            case TOTP_REQUIRED -> {
                body.put("success", false);
                body.put("message", "Tài khoản đã bật xác thực 2 lớp, cần mã 6 số.");
                body.put("data", Map.of("code", "TOTP_REQUIRED", "challengeId", outcome.challengeId()));
            }
            case TOTP_INVALID -> {
                body.put("success", false);
                body.put("message", "Mã xác thực không đúng hoặc đã hết hạn.");
                body.put("data", outcome.challengeId() == null
                        ? Map.of("code", "TOTP_INVALID")
                        : Map.of("code", "TOTP_INVALID", "challengeId", outcome.challengeId()));
            }
            case LOCKED -> {
                body.put("success", false);
                body.put("message", "Nhập sai mã quá nhiều lần, vui lòng thử lại sau.");
                body.put("data", Map.of("code", "LOCKED", "retryAfterSeconds", outcome.retryAfterSeconds()));
            }
            default -> { // BAD_CREDENTIALS
                body.put("success", false);
                body.put("message", "Mã quản trị viên hoặc mật khẩu không chính xác");
                body.put("data", null);
            }
        }
        return body;
    }
}
