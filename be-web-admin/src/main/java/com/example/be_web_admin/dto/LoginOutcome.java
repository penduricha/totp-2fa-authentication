package com.example.be_web_admin.dto;

import com.example.be_web_admin.enums.LoginStatus;

public record LoginOutcome(LoginStatus status, String accessToken, String refreshToken,
                           long retryAfterSeconds, String challengeId) {

    public static LoginOutcome of(LoginStatus status) {
        return new LoginOutcome(status, null, null, 0, null);
    }

    public static LoginOutcome locked(long retryAfterSeconds) {
        return new LoginOutcome(LoginStatus.LOCKED, null, null, retryAfterSeconds, null);
    }

    public static LoginOutcome ok(String accessToken, String refreshToken) {
        return new LoginOutcome(LoginStatus.OK, accessToken, refreshToken, 0, null);
    }

    public static LoginOutcome challenge(String challengeId) {
        return withChallenge(LoginStatus.TOTP_REQUIRED, challengeId);
    }

    public static LoginOutcome withChallenge(LoginStatus status, String challengeId) {
        return new LoginOutcome(status, null, null, 0, challengeId);
    }
}
