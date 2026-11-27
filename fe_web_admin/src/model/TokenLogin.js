// Browser access tokens are deliberately memory-only. The long-lived refresh token is
// held by the server in an HttpOnly, Secure-in-production, SameSite=Strict cookie.
let accessTokenInMemory = null;

function clearLegacyTokenStorage() {
    try {
        localStorage.removeItem("accessToken");
        localStorage.removeItem("refreshToken");
    } catch {
        // Storage can be disabled in private browsing; authentication still uses the cookie.
    }
}

clearLegacyTokenStorage();

export class TokenLogin {
    #accessToken;

    constructor(accessToken = null) {
        this.#accessToken = accessToken == null ? null : String(accessToken);
        if (this.#accessToken) accessTokenInMemory = this.#accessToken;
    }

    getAccessToken() {
        return this.#accessToken ?? accessTokenInMemory;
    }

    setAccessToken(value) {
        this.#accessToken = value == null ? null : String(value);
        accessTokenInMemory = this.#accessToken;
    }

    // Legacy API names remain for callers, but access token is never persisted.
    setAccessTokenToStorage() {
        accessTokenInMemory = this.#accessToken;
    }

    getAccessTokenFromStorage() {
        this.#accessToken = accessTokenInMemory;
        return accessTokenInMemory;
    }

    setRefreshToken() {
        // The refresh token is HttpOnly and cannot be accessed by JavaScript.
    }

    getRefreshToken() {
        return null;
    }

    setRefreshTokenToStorage() {
        // Intentionally empty: do not expose/persist refresh credentials in JavaScript.
    }

    getRefreshTokenFromStorage() {
        clearLegacyTokenStorage();
        return null;
    }

    removeAccessTokenAndRefreshTokenFromStorage() {
        accessTokenInMemory = null;
        this.#accessToken = null;
        clearLegacyTokenStorage();
    }

    toString() {
        return "TokenLogin [browser tokens are not serializable]";
    }
}
