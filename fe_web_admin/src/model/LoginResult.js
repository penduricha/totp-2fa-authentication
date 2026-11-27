/**
 * Kết quả một lần gọi đăng nhập, để component không phải đọc JSON thô.
 * status là một giá trị của REQUEST_STATUS (AdminRepository.js).
 */
export class LoginResult {
    constructor(status, retryAfterSeconds = 0, challengeId = null) {
        this.status = status;
        this.retryAfterSeconds = retryAfterSeconds;
        this.challengeId = challengeId;
    }

    getStatus() {
        return this.status;
    }

    getRetryAfterSeconds() {
        return this.retryAfterSeconds;
    }

    getChallengeId() {
        return this.challengeId;
    }
}