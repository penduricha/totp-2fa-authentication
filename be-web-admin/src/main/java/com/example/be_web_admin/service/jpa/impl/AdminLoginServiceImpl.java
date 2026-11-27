package com.example.be_web_admin.service.jpa.impl;

import com.example.be_web_admin.dto.AdminLoginDTO;
import com.example.be_web_admin.dto.LoginOutcome;
import com.example.be_web_admin.enums.AuthEvent;
import com.example.be_web_admin.enums.LoginStatus;
import com.example.be_web_admin.model.jpa.Admin;
import com.example.be_web_admin.model.jpa.TotpCredential;
import com.example.be_web_admin.model.redis.LoginChallenge;
import com.example.be_web_admin.repository.redis.LoginChallengeRepository;
import com.example.be_web_admin.repository.jpa.AdminRepository;
import com.example.be_web_admin.repository.jpa.TotpCredentialRepository;
import com.example.be_web_admin.security.password.MgmtPassword;
import com.example.be_web_admin.security.private_key.CryptoService;
import com.example.be_web_admin.security.totp.TotpLoginVerifier;
import com.example.be_web_admin.service.redis.AdminTokenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Đăng nhập một request duy nhất gồm adminId, password và totpCode (tùy chọn):
 *  - Chưa bật 2FA: adminId + password là vào, totpCode để trống.
 *  - Đã bật 2FA: bắt buộc totpCode; thiếu -> TOTP_REQUIRED, sai/hết hạn/đã dùng -> TOTP_INVALID.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminLoginServiceImpl {

    private static final int MAX_TOTP_FAILS = 5;
    private static final Duration TOTP_FAIL_WINDOW = Duration.ofMinutes(5);
    private static final String TOTP_FAIL_KEY = "totp:fail:";
    private static final String LOGIN_IP_FAIL_KEY = "login:ip-fail:";
    private static final int MAX_LOGIN_FAILURES_PER_IP = 20;
    private static final Duration LOGIN_IP_FAIL_WINDOW = Duration.ofMinutes(1);

    private final AdminRepository adminRepository;
    private final TotpCredentialRepository totpCredentialRepository;
    private final LoginChallengeRepository loginChallengeRepository;
    private final MgmtPassword mgmtPassword;
    private final TotpLoginVerifier totpLoginVerifier;
    private final CryptoService cryptoService;
    private final AuthAuditService auditService;
    private final StringRedisTemplate redis;

    // Inject interface (không inject AdminTokenServiceImpl): đã tạo JWT và lưu access/refresh token vào Redis
    private final AdminTokenService adminTokenService;

    /** Password phase for the Web flow. TOTP-enabled accounts get only an opaque 3-minute challenge. */
    public LoginOutcome startWebLogin(AdminLoginDTO req, String ip) {
        if (req == null || req.getAdminId() == null || req.getPassword() == null) {
            return LoginOutcome.of(LoginStatus.BAD_CREDENTIALS);
        }
        long retryAfter = ipRateLimitRetryAfter(ip);
        if (retryAfter > 0) return LoginOutcome.locked(retryAfter);

        Admin admin = adminRepository.findAdminByAdminId(req.getAdminId());
        String storedHash = admin == null ? null : admin.getPassword();
        boolean passwordOk = mgmtPassword.matches(req.getPassword(), storedHash);
        if (admin == null || !passwordOk) {
            retryAfter = recordIpFailure(ip);
            return retryAfter > 0 ? LoginOutcome.locked(retryAfter)
                    : LoginOutcome.of(LoginStatus.BAD_CREDENTIALS);
        }

        Optional<TotpCredential> enabled = totpCredentialRepository
                .findFirstByAdmin_AdminIdAndConfirmedTrueOrderByConfirmedAtDesc(admin.getAdminId());
        if (enabled.isEmpty()) return issueTokens(admin);

        String challengeId = UUID.randomUUID().toString();
        LoginChallenge challenge = new LoginChallenge();
        challenge.setChallengeId(challengeId);
        challenge.setAdminId(admin.getAdminId());
        challenge.setIp(ip);
        challenge.setAttempts(0);
        challenge.setCreatedAt(Instant.now());
        loginChallengeRepository.save(challenge);
        return LoginOutcome.challenge(challengeId);
    }

    /** Complete the Web second factor using only its short-lived, single-use challenge. */
    public LoginOutcome completeWebLogin(String challengeId, String code, String ip, String userAgent) {
        long retryAfter = ipRateLimitRetryAfter(ip);
        if (retryAfter > 0) return LoginOutcome.locked(retryAfter);
        if (challengeId == null || code == null) {
            return LoginOutcome.of(LoginStatus.BAD_CREDENTIALS);
        }

        LoginChallenge challenge = loginChallengeRepository.findById(challengeId).orElse(null);
        if (challenge == null || challenge.getAdminId() == null
                || !sameNetwork(challenge.getIp(), ip)) {
            retryAfter = recordIpFailure(ip);
            return retryAfter > 0 ? LoginOutcome.locked(retryAfter)
                    : LoginOutcome.of(LoginStatus.BAD_CREDENTIALS);
        }

        Long adminId = challenge.getAdminId();
        String failKey = TOTP_FAIL_KEY + adminId;
        String failStr = redis.opsForValue().get(failKey);
        int fails = failStr == null ? 0 : Integer.parseInt(failStr);
        if (fails >= MAX_TOTP_FAILS) {
            Long ttl = redis.getExpire(failKey);
            return LoginOutcome.locked(ttl == null || ttl < 1 ? TOTP_FAIL_WINDOW.toSeconds() : ttl);
        }

        Admin admin = adminRepository.findAdminByAdminId(adminId);
        Optional<TotpCredential> credential = totpCredentialRepository
                .findFirstByAdmin_AdminIdAndConfirmedTrueOrderByConfirmedAtDesc(adminId);
        if (admin == null || credential.isEmpty()) {
            loginChallengeRepository.deleteById(challengeId);
            return LoginOutcome.of(LoginStatus.BAD_CREDENTIALS);
        }

        TotpCredential cred = credential.get();
        final String secretBase32;
        try {
            secretBase32 = cryptoService.decrypt(cred.getKdfSalt(), cred.getIv(), cred.getSecretCipher(),
                    cred.getKeyVersion(), adminId);
        } catch (CryptoService.CryptoTamperedException e) {
            auditService.log(adminId, AuthEvent.TOTP_LOGIN_FAILED, false, ip, userAgent,
                    "credential ciphertext authentication failed (possible tampering)");
            loginChallengeRepository.deleteById(challengeId);
            return LoginOutcome.of(LoginStatus.BAD_CREDENTIALS);
        }

        TotpLoginVerifier.Outcome outcome = totpLoginVerifier.verify(
                adminId, secretBase32, cred.getPeriodSeconds(), cred.getDigits(),
                cred.getAlgorithm(), code.trim(), Instant.now());
        if (outcome.result() == TotpLoginVerifier.Result.OK) {
            String consumeKey = "login:challenge:consumed:" + challengeId;
            Boolean firstCompletion = redis.opsForValue().setIfAbsent(consumeKey, "1", Duration.ofMinutes(3));
            if (!Boolean.TRUE.equals(firstCompletion)) {
                return LoginOutcome.withChallenge(LoginStatus.TOTP_INVALID, challengeId);
            }
            loginChallengeRepository.deleteById(challengeId);
            redis.delete(failKey);
            auditService.log(adminId, AuthEvent.TOTP_LOGIN_OK, true, ip, userAgent,
                    "web challenge offset=" + outcome.matchedOffset());
            return issueTokens(admin);
        }

        Long count = redis.opsForValue().increment(failKey);
        if (count != null && count == 1L) redis.expire(failKey, TOTP_FAIL_WINDOW);
        retryAfter = recordIpFailure(ip);
        auditService.log(adminId, AuthEvent.TOTP_LOGIN_FAILED, false, ip, userAgent,
                outcome.result() == TotpLoginVerifier.Result.REPLAYED ? "replayed code" : "wrong code");
        if (count != null && count >= MAX_TOTP_FAILS) {
            redis.expire(failKey, TOTP_FAIL_WINDOW);
            loginChallengeRepository.deleteById(challengeId);
            return LoginOutcome.locked(TOTP_FAIL_WINDOW.toSeconds());
        }
        if (retryAfter > 0) return LoginOutcome.locked(retryAfter);
        return LoginOutcome.withChallenge(LoginStatus.TOTP_INVALID, challengeId);
    }

    private boolean sameNetwork(String previousIp, String currentIp) {
        return previousIp != null && currentIp != null && previousIp.equals(currentIp);
    }

    public LoginOutcome login(AdminLoginDTO req, String ip, String userAgent) {
        if (req == null || req.getAdminId() == null || req.getPassword() == null) {
            return LoginOutcome.of(LoginStatus.BAD_CREDENTIALS);
        }
        Long adminId = req.getAdminId();

        long ipRetryAfter = ipRateLimitRetryAfter(ip);
        if (ipRetryAfter > 0) {
            return LoginOutcome.locked(ipRetryAfter);
        }

        // (a) Mật khẩu BCrypt. Cột password trong DB ĐÃ là hash BCrypt, so khớp thẳng với mật khẩu người dùng nhập
        //     (không được băm lại hash đó). Admin không tồn tại: matches() vẫn chạy 1 lần BCrypt giả
        //     để thời gian phản hồi không lộ tài khoản có tồn tại hay không.
        Admin admin = adminRepository.findAdminByAdminId(adminId);
        String storedHash = (admin == null) ? null : admin.getPassword();
        boolean passwordOk = mgmtPassword.matches(req.getPassword(), storedHash); // luôn chạy, kể cả admin = null
        if (admin == null || !passwordOk) {
            long retryAfter = recordIpFailure(ip);
            return retryAfter > 0 ? LoginOutcome.locked(retryAfter) : LoginOutcome.of(LoginStatus.BAD_CREDENTIALS);
        }

        // (b) Chỉ credential ĐÃ xác nhận bên mobile mới tính là bật 2FA (pending không tính).
        //     - Chưa bật 2FA: bỏ qua ô mã 6 số (kể cả khi có gửi lên) -> vào luôn.
        //     - Đã bật 2FA: BẮT BUỘC có mã 6 số. Chỉ trả TOTP_REQUIRED sau khi mật khẩu đã đúng.
        Optional<TotpCredential> credOpt = totpCredentialRepository
                .findFirstByAdmin_AdminIdAndConfirmedTrueOrderByConfirmedAtDesc(adminId);
        if (credOpt.isEmpty()) {
            return issueTokens(admin);
        }

        String code = req.getTotpCode() == null ? null : req.getTotpCode().trim();
        if (code == null || code.isEmpty()) {
            return LoginOutcome.of(LoginStatus.TOTP_REQUIRED);
        }

        // (c) Khóa tạm khi nhập sai mã nhiều lần. Chỉ tính SAU khi mật khẩu đã đúng,
        //     nên người không biết mật khẩu không thể cố ý khóa tài khoản của admin.
        String failKey = TOTP_FAIL_KEY + adminId;
        String failStr = redis.opsForValue().get(failKey);
        int fails = (failStr == null) ? 0 : Integer.parseInt(failStr);
        if (fails >= MAX_TOTP_FAILS) {
            Long ttl = redis.getExpire(failKey);
            return LoginOutcome.locked((ttl == null || ttl < 1) ? TOTP_FAIL_WINDOW.toSeconds() : ttl);
        }

        // (d) Giải mã secret (cùng cách EnrollmentConfirmServiceImpl) rồi dò mã trong cửa sổ chu kỳ lân cận
        TotpCredential cred = credOpt.get();
        String secretBase32;
        try {
            // adminId lấy từ tham số, không dùng cred.getAdmin() (proxy LAZY)
            secretBase32 = cryptoService.decrypt(cred.getKdfSalt(), cred.getIv(), cred.getSecretCipher(),
                    cred.getKeyVersion(), adminId);
        } catch (CryptoService.CryptoTamperedException e) {
            auditService.log(adminId, AuthEvent.TOTP_LOGIN_FAILED, false, ip, userAgent,
                    "credential ciphertext authentication failed (possible tampering)");
            return LoginOutcome.of(LoginStatus.TOTP_INVALID);
        }

        TotpLoginVerifier.Outcome outcome = totpLoginVerifier.verify(
                adminId, secretBase32, cred.getPeriodSeconds(), cred.getDigits(),
                cred.getAlgorithm(), code, Instant.now());

        if (outcome.result() == TotpLoginVerifier.Result.OK) {
            redis.delete(failKey);
            // offset: -1 = mã chu kỳ trước (POST tới trễ), 0 = đúng chu kỳ, +1 = app chạy nhanh. Dùng để thống kê độ trễ cho luận văn.
            auditService.log(adminId, AuthEvent.TOTP_LOGIN_OK, true, ip, userAgent,
                    "offset=" + outcome.matchedOffset());
            return issueTokens(admin);
        }

        Long count = redis.opsForValue().increment(failKey);
        if (count != null && count == 1L) {
            redis.expire(failKey, TOTP_FAIL_WINDOW);
        }
        long ipRetryAfter = recordIpFailure(ip);
        // Không ghi mã người dùng nhập vào log
        auditService.log(adminId, AuthEvent.TOTP_LOGIN_FAILED, false, ip, userAgent,
                outcome.result() == TotpLoginVerifier.Result.REPLAYED ? "replayed code" : "wrong code");

        // Lần sai thứ MAX_TOTP_FAILS: khóa NGAY (không đợi lần thử thứ MAX + 1) để web khóa form và đếm ngược.
        // Đặt lại TTL đúng 5 phút kể từ lần sai cuối, nên thời gian khóa luôn đủ 5 phút
        // (nếu giữ TTL tính từ lần sai đầu tiên thì có thể chỉ còn vài giây).
        if (count != null && count >= MAX_TOTP_FAILS) {
            redis.expire(failKey, TOTP_FAIL_WINDOW);
            return LoginOutcome.locked(TOTP_FAIL_WINDOW.toSeconds());
        }
        if (ipRetryAfter > 0) {
            return LoginOutcome.locked(ipRetryAfter);
        }
        // INVALID và REPLAYED trả chung một thông báo để không lộ thêm thông tin
        return LoginOutcome.of(LoginStatus.TOTP_INVALID);
    }

    private long ipRateLimitRetryAfter(String ip) {
        String key = loginIpFailKey(ip);
        String value = redis.opsForValue().get(key);
        if (value == null) return 0;
        try {
            if (Long.parseLong(value) < MAX_LOGIN_FAILURES_PER_IP) return 0;
        } catch (NumberFormatException ignored) {
            return LOGIN_IP_FAIL_WINDOW.toSeconds();
        }
        Long ttl = redis.getExpire(key);
        return ttl == null || ttl < 1 ? LOGIN_IP_FAIL_WINDOW.toSeconds() : ttl;
    }

    private long recordIpFailure(String ip) {
        String key = loginIpFailKey(ip);
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, LOGIN_IP_FAIL_WINDOW);
        }
        if (count == null || count < MAX_LOGIN_FAILURES_PER_IP) return 0;
        Long ttl = redis.getExpire(key);
        return ttl == null || ttl < 1 ? LOGIN_IP_FAIL_WINDOW.toSeconds() : ttl;
    }

    private String loginIpFailKey(String ip) {
        String normalized = ip == null || ip.isBlank() ? "unknown" : ip.trim();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8));
            return LOGIN_IP_FAIL_KEY + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }

    private LoginOutcome issueTokens(Admin admin) {
        // createTokenAdmin: sinh access + refresh token, lưu Redis; trả map rỗng nếu không tạo được
        Map<String, Object> tokens = adminTokenService.createTokenAdmin(admin.getAdminId());
        Object accessToken = tokens.get("accessToken");
        Object refreshToken = tokens.get("refreshToken");
        if (accessToken == null || refreshToken == null) {
            throw new IllegalStateException("Không tạo được token cho admin " + admin.getAdminId());
        }
        return LoginOutcome.ok(String.valueOf(accessToken), String.valueOf(refreshToken));
    }
}