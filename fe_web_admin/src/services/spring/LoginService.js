import {TokenLogin} from "@/model/TokenLogin.js";
import {AdminService} from "@/services/spring/AdminService.js";

export class LoginService {
    async getStatusAutoLogin() {
        // Refresh credentials are HttpOnly: JS cannot inspect whether the cookie exists.
        // Ask the server to resume/refresh the browser session instead.
        const tokenLogin = new TokenLogin();
        const adminService = new AdminService();
        return Boolean(await adminService.getDataToAutoLoginAccessToken(tokenLogin));
    }
}
