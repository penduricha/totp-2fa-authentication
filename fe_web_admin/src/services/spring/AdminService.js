import {AdminAccount} from "@/model/AdminAccount.js";
import {TokenLogin} from "@/model/TokenLogin.js";
import SpringConfigHeader from "@/services/spring/SpringConfigHeader.js";
import AdminStorage from "@/local_storage/AdminStorage.js";
import {REFRESH_OUTCOME, silentRefresher} from "@/services/auth/SilentTokenRefresher.js";

const ENDPOINTS = Object.freeze({
    LOGIN: '/admin/auth/login',
    LOGIN_TOTP: '/admin/auth/login/totp',
    LOGOUT: '/admin/auth/logout',
    INFORMATION: '/admin/information/basic',
    INFORMATION_DETAIL: '/admin/information/detail',
    AUTO_LOGIN_ACCESS_TOKEN: '/admin/auth/login/auto/access-token',
    AUTO_LOGIN_REFRESH_TOKEN: '/admin/auth/login/auto/refresh-token',
});

const HTTP_OK = 200;
const HTTP_UNAUTHORIZED = 401;
const HTTP_FORBIDDEN = 403;

function assertInstanceOf(value, expectedClass, label) {
    if (!(value instanceof expectedClass)) {
        throw new Error(`Invalid ${label} instance`);
    }
}

export class AdminService {
    constructor(
        springConfigHeader = new SpringConfigHeader(),
        adminLocalStorage = new AdminStorage(),
    ) {
        this.springConfigHeader = springConfigHeader;
        this.adminLocalStorage = adminLocalStorage;
    }

    // ---------- Public API ----------

    /** Bước 1: chỉ mã quản trị viên + mật khẩu. */
    async postDataToLoginRequest(adminAccount) {
        assertInstanceOf(adminAccount, AdminAccount, 'AdminAccount');

        return this.#postWithAuthorization(ENDPOINTS.LOGIN, {
            adminId: adminAccount.getAdminId(),
            password: adminAccount.getPassword(),
        });
    }

    /** Complete the short-lived server challenge; do not resend the password in phase two. */
    async postDataToLoginTotpRequest(challengeId, totpCode) {
        return this.#postWithAuthorization(ENDPOINTS.LOGIN_TOTP, {
            challengeId: String(challengeId ?? ''),
            totpCode: String(totpCode ?? ''),
        });
    }

    async postDataToLogoutRequest(tokenLogin) {
        assertInstanceOf(tokenLogin, TokenLogin, 'TokenLogin');

        return this.springConfigHeader
            .getAPIClientWithSilentRefresh()
            .post(ENDPOINTS.LOGOUT, {});
    }

    async getAdminInformationBasicRequestByAdminId(adminId) {
        try {
            const path = `${ENDPOINTS.INFORMATION}?adminId=${encodeURIComponent(adminId)}`;
            return await this.springConfigHeader
                .getAPIClientNoHeaders()
                .get(path, this.springConfigHeader.getAuthorization());
        } catch (error) {
            console.error(error);
            return null;
        }
    }

    async getAdminInformationDetailRequestByAdminId(adminId) {
        try {
            const path = `${ENDPOINTS.INFORMATION_DETAIL}?adminId=${encodeURIComponent(adminId)}`;
            return await this.springConfigHeader
                .getAPIClientNoHeaders()
                .get(path, this.springConfigHeader.getAuthorization());
        } catch (error) {
            console.error(error);
            return null;
        }
    }

    /**
     * Tự động đăng nhập bằng access token.
     * Nếu access token hết hạn (401) thì thử làm mới token rồi đăng nhập lại.
     * Lỗi mạng / server tạm thời KHÔNG xóa phiên: chỉ trả false, token vẫn còn để lần sau thử lại.
     * @returns {Promise<boolean>} true nếu đăng nhập thành công
     */
    async getDataToAutoLoginAccessToken(tokenLogin) {
        assertInstanceOf(tokenLogin, TokenLogin, 'TokenLogin');
        tokenLogin.getAccessTokenFromStorage();
        if (!tokenLogin.getAccessToken()) {
            return this.#renewTokensAndLogin(tokenLogin);
        }

        try {
            return await this.#loginWithAccessToken(tokenLogin.getAccessToken());
        } catch (error) {
            if (error.response?.status !== HTTP_UNAUTHORIZED) {
                console.warn('Auto login skipped (network/server error), session kept:', error);
                return false;
            }
        }

        return this.#renewTokensAndLogin(tokenLogin);
    }

    /**
     * Đổi refresh token lấy CẶP TOKEN MỚI (server xoay vòng: refresh token cũ bị hủy ngay).
     * Chỉ gọi hàm này qua SilentTokenRefresher (single-flight + Web Locks), đừng gọi song song.
     *
     * @returns {Promise<'OK'|'REJECTED'|'TRANSIENT'>}
     *   OK        : đã lưu token mới vào storage.
     *   REJECTED  : server từ chối (401/403, hoặc trả 200 mà không có token): phiên đã mất -> xóa phiên cục bộ.
     *   TRANSIENT : mất mạng, timeout, 5xx...: KHÔNG xóa gì, nơi gọi tự thử lại.
     */
    async refreshTokens(tokenLogin) {
        assertInstanceOf(tokenLogin, TokenLogin, 'TokenLogin');

        try {
            const response = await this.springConfigHeader
                .getAPIClientNoHeaders()
                .post(ENDPOINTS.AUTO_LOGIN_REFRESH_TOKEN, {});

            const {accessToken, adminId} = response?.data?.data ?? {};
            if (response?.status !== HTTP_OK || !accessToken) {
                this.#clearSession(tokenLogin);
                return REFRESH_OUTCOME.REJECTED;
            }

            this.#storeAccessToken(tokenLogin, accessToken);
            if (adminId !== undefined && adminId !== null) this.#saveAdminId(adminId);
            return REFRESH_OUTCOME.OK;
        } catch (error) {
            const status = error.response?.status;
            if (status === HTTP_UNAUTHORIZED || status === HTTP_FORBIDDEN) {
                this.#clearSession(tokenLogin);
                return REFRESH_OUTCOME.REJECTED;
            }
            console.warn('Refresh token failed temporarily, session kept:', error);
            return REFRESH_OUTCOME.TRANSIENT;
        }
    }

    // ---------- Private helpers ----------

    #postWithAuthorization(path, data) {
        return this.springConfigHeader
            .getAPIClientNoHeaders()
            .post(path, data, this.springConfigHeader.getAuthorization());
    }

    async #loginWithAccessToken(accessToken) {
        const response = await this.springConfigHeader
            .getAPIClientWithHeadersToken(accessToken)
            .get(ENDPOINTS.AUTO_LOGIN_ACCESS_TOKEN);

        if (response.status !== HTTP_OK) {
            return false;
        }

        this.#saveAdminId(response.data.data.adminId);
        return true;
    }

    async #renewTokensAndLogin(tokenLogin) {
        console.warn('Access token is expired. Renewing tokens...');

        // Đi qua bộ làm mới ngầm để chung 1 lần gọi (single-flight) với các request / tab khác:
        // refresh token chỉ dùng được MỘT lần, hai lần gọi song song thì lần hai bị 401 và phiên bị xóa oan.
        // expireOnReject = false: luồng này tự xử lý "chưa đăng nhập" (không ép tải lại trang /login).
        // Bộ làm mới chưa init (vd. chạy riêng lẻ trong test) thì gọi thẳng.
        const refreshed = silentRefresher.isReady()
            ? await silentRefresher.refreshNow(tokenLogin.getAccessToken(), {expireOnReject: false})
            : (await this.refreshTokens(tokenLogin)) === REFRESH_OUTCOME.OK;

        if (!refreshed) {
            return false;
        }

        try {
            // Dùng token mới nhất trong storage (có thể do tab khác làm mới)
            tokenLogin.getAccessTokenFromStorage();
            return await this.#loginWithAccessToken(tokenLogin.getAccessToken());
        } catch (error) {
            console.warn('Auto login after refresh failed, session kept:', error);
            return false;
        }
    }

    #storeAccessToken(tokenLogin, accessToken) {
        tokenLogin.setAccessToken(accessToken);
    }

    #saveAdminId(adminId) {
        this.adminLocalStorage.setAdminIdToLocalStorage(Number(adminId));
    }

    #clearSession(tokenLogin) {
        tokenLogin.removeAccessTokenAndRefreshTokenFromStorage();
        this.adminLocalStorage.removeAdminIdFromLocalStorage();
    }
}