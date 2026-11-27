export default class AdminStorage {
    // Định nghĩa các key cố định dưới dạng static readonly (hoặc hằng số)
    static STORAGE_KEYS = {
        ADMIN_ID: 'adminIdLocalStorage',
        REMEMBER_LOGIN: 'adminRememberLogin'
    };

    /**
     * Lưu thông tin admin vào localStorage
     * @param {Object} admin
     */
    // setAdminLocalStorage(admin) {
    //     if (!admin) {
    //         console.error('Admin object is empty.');
    //         return;
    //     }
    //     localStorage.setItem(AdminStorage.STORAGE_KEYS.ADMIN, JSON.stringify(admin));
    // }
    /**
     * Remember only the account identifier. Never persist passwords in browser storage.
     * @param {number|string} adminId
     */
    saveLocalStorageRememberMe(adminId) {
        if (!adminId) return;
        localStorage.setItem(
            AdminStorage.STORAGE_KEYS.REMEMBER_LOGIN,
            JSON.stringify({ adminId: String(adminId) })
        );
    }

    /**
     * Lấy thông tin Remember Me
     * @returns {Object}
     */
    getLocalStorageRememberMe() {
        const key = AdminStorage.STORAGE_KEYS.REMEMBER_LOGIN;
        const data = localStorage.getItem(key);
        if (!data) return {};
        try {
            const parsed = JSON.parse(data);
            if (!parsed?.adminId) {
                localStorage.removeItem(key);
                return {};
            }
            // Migrate old installations by deleting the previously persisted plaintext password.
            const safeValue = { adminId: String(parsed.adminId) };
            localStorage.setItem(key, JSON.stringify(safeValue));
            return safeValue;
        } catch {
            localStorage.removeItem(key);
            return {};
        }
    }

    /**
     * Xóa thông tin Remember Me
     */
    removeLocalStorageRememberMe() {
        const key = AdminStorage.STORAGE_KEYS.REMEMBER_LOGIN;
        if (localStorage.getItem(key)) {
            localStorage.removeItem(key);
        }
    }

    /**
     * Lưu riêng adminId vào localStorage
     *
     */
    setAdminIdToLocalStorage(adminId) {
        if (!adminId) {
            console.error('Admin ID is empty.');
            return;
        }
        localStorage.setItem(AdminStorage.STORAGE_KEYS.ADMIN_ID, adminId);
    }

    getAdminIdToLocalStorage() {
        return localStorage.getItem(AdminStorage.STORAGE_KEYS.ADMIN_ID);
    }

    /**
     * Xóa riêng adminId khỏi localStorage
     */
    removeAdminIdFromLocalStorage() {
        const key = AdminStorage.STORAGE_KEYS.ADMIN_ID;
        if (localStorage.getItem(key)) {
            localStorage.removeItem(key);
        } else {
            console.warn('Admin ID local storage is already empty.');
        }
    }
}