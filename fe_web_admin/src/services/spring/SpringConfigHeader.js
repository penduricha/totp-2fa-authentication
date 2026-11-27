import axios, {AxiosError} from "axios";
import NetworkConfiguration from "@/services/NetworkConfiguration.js";
import {TokenLogin} from "@/model/TokenLogin.js";
import {REFRESH_OUTCOME, silentRefresher} from "@/services/auth/SilentTokenRefresher.js";

const networkConfig = new NetworkConfiguration();
const HTTP_UNAUTHORIZED = 401;

// "Bearer xxx" -> "xxx" (headers của axios 1.x là AxiosHeaders, cũng có thể là object thường)
function readBearerToken(headers) {
    const value = typeof headers?.get === "function" ? headers.get("Authorization") : headers?.Authorization;
    return typeof value === "string" ? value.replace(/^Bearer\s+/i, "") : null;
}

export default class SpringConfigHeader {

    #createClient(extraHeaders = {}) {
        return axios.create({
            baseURL: networkConfig.requestSpringBoot(),
            timeout: networkConfig.requestTimeout,
            withCredentials: true,
            headers: {
                "Content-Type": "application/json",
                ...extraHeaders,
            },
        });
    }

    // Không có header xác thực (đăng nhập, API công khai)
    getAPIClientNoHeaders() {
        return this.#createClient();
    }

    // JWT access token: Authorization: Bearer <token>. Browser builds contain no shared credentials.
    getAPIClientWithHeadersToken(token) {
        return this.#createClient({ Authorization: `Bearer ${token}` });
    }

    // Client cho các API cần đăng nhập, TỰ LÀM MỚI TOKEN NGẦM:
    //  - Mỗi request đọc access token mới nhất trong storage (không dùng token cũ đã chụp lúc tạo client).
    //  - Server trả 401 (access token hết TTL) -> làm mới bằng refresh token (chung 1 lần với các request
    //    khác) rồi gửi lại ĐÚNG 1 lần. Người dùng không thấy gì, không có thông báo "phiên hết hạn".
    //  - Làm mới thất bại vì mạng / server tạm lỗi -> báo lỗi mạng (không có response), KHÔNG báo hết phiên.
    //  - Refresh token bị từ chối -> trả 401 gốc; SilentTokenRefresher đồng thời đưa người dùng về /login.
    // Không dùng cho chính API login / refresh (tránh lặp vô hạn): hai API đó dùng getAPIClientWithHeadersToken.
    getAPIClientWithSilentRefresh() {
        const client = this.#createClient();

        client.interceptors.request.use((config) => {
            const token = new TokenLogin().getAccessTokenFromStorage();
            if (token) {
                config.headers.set("Authorization", `Bearer ${token}`);
            }
            return config;
        });

        client.interceptors.response.use(undefined, async (error) => {
            const config = error.config;
            if (error.response?.status !== HTTP_UNAUTHORIZED || !config || config._silentRetried) {
                throw error;
            }
            config._silentRetried = true;

            const refreshed = await silentRefresher.refreshNow(readBearerToken(config.headers));
            if (refreshed) {
                return client.request(config); // chạy lại request interceptor -> gắn token mới
            }
            if (silentRefresher.lastOutcome === REFRESH_OUTCOME.TRANSIENT) {
                throw new AxiosError("Token refresh failed temporarily", AxiosError.ERR_NETWORK, config);
            }
            throw error;
        });

        return client;
    }

    // Legacy Axios config helper used by account-information and logout requests.
    // The API is bearer-token based; never rely on ambient browser credentials.
    getAuthorization() {
        const accessToken = new TokenLogin().getAccessTokenFromStorage();
        return {
            headers: accessToken ? { Authorization: `Bearer ${accessToken}` } : {},
            withCredentials: true,
        };
    }
}