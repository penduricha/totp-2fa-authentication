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

    #authUsername;
    #authPassword;

    constructor() {
        this.#authUsername = String(import.meta.env.VITE_AUTH_USERNAME ?? "").trim();
        this.#authPassword = String(import.meta.env.VITE_AUTH_PASSWORD ?? "").trim();
    }

    // Có cấu hình Basic Auth trong .env hay không
    #hasBasicAuth() {
        return this.#authUsername !== "" || this.#authPassword !== "";
    }

    #basicAuthHeader() {
        return `Basic ${btoa(`${this.#authUsername}:${this.#authPassword}`)}`;
    }

    #createClient(extraHeaders = {}) {
        return axios.create({
            baseURL: networkConfig.requestSpringBoot(),
            timeout: networkConfig.requestTimeout,
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

    // JWT access token: Authorization: Bearer <token>
    // withBasicAuth = true thì đẩy thêm Basic Auth vào header riêng X-Client-Auth
    // (tránh đè Authorization).
    getAPIClientWithHeadersToken(token, withBasicAuth = false) {
        return this.#createClient({
            Authorization: `Bearer ${token}`,
            ...(withBasicAuth && this.#hasBasicAuth()
                ? { "X-Client-Auth": this.#basicAuthHeader() }
                : {}),
        });
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

    // Giữ nguyên hành vi cũ cho các nơi đang dùng getAuthorization()
    getAuthorization() {
        return {
            auth: {},
            withCredentials: true,
        };
    }
}