// src/services/auth/SilentTokenRefresher.js
//
// Làm mới access token NGẦM bằng refresh token, người dùng không thấy gì.
// Ba lớp bảo vệ:
//   1. Chủ động : hẹn giờ, làm mới khi access token còn <= REFRESH_BEFORE_MS (đọc `exp` trong JWT).
//   2. Thức dậy : tab nền / máy ngủ làm setTimeout bị trễ -> kiểm tra lại khi tab hiện lại hoặc có mạng lại.
//   3. Bị động  : API trả 401 -> làm mới 1 lần rồi gửi lại request
//                 (axios: interceptor trong SpringConfigHeader.getAPIClientWithSilentRefresh;
//                  fetch : hàm retryOn401 ở cuối file).
// Chống lặp: nhiều request / nhiều tab cùng hết hạn thì chỉ MỘT lần gọi refresh (single-flight + Web Locks).
//
// Kết quả một lần làm mới (REFRESH_OUTCOME):
//   OK        : đã có cặp token mới trong storage.
//   REJECTED  : server từ chối refresh token (hết hạn / bị thu hồi) -> phải đăng nhập lại.
//   TRANSIENT : lỗi mạng / server tạm thời -> GIỮ phiên, tự thử lại, KHÔNG đăng xuất.

export const REFRESH_OUTCOME = Object.freeze({
  OK: 'OK',
  REJECTED: 'REJECTED',
  TRANSIENT: 'TRANSIENT',
})

const REFRESH_BEFORE_MS = 60_000            // access token sống 5 phút -> làm mới khi còn <= 60 giây
const MIN_DELAY_MS = 5_000                  // chặn vòng lặp dày nếu server trả token có exp không đổi
const FALLBACK_INTERVAL_MS = 4 * 60_000     // token không phải JWT (không đọc được exp) -> làm mới định kỳ
const MAX_TIMEOUT_MS = 2 ** 31 - 1          // giới hạn của setTimeout
const RETRY_DELAYS_MS = [5_000, 15_000, 30_000] // lỗi mạng: thử lại, KHÔNG đăng xuất
const LOCK_NAME = 'auth-token-refresh'

/** Đọc `exp` (ms) từ JWT. Trả null nếu không phải JWT. */
function decodeExpMs(token) {
  try {
    const payload = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')
    const { exp } = JSON.parse(atob(payload))
    return typeof exp === 'number' ? exp * 1000 : null
  } catch {
    return null
  }
}

class SilentTokenRefresher {
  constructor() {
    this.tokenLogin = null
    this.adminService = null
    this.onSessionExpired = null
    this.timer = null
    this.retryTimer = null
    this.retryCount = 0
    this.inflight = null
    this.started = false
    this.expiring = false
    /** Kết quả của lần làm mới gần nhất (REFRESH_OUTCOME). Nơi gọi đọc để phân biệt "hết phiên" và "lỗi mạng". */
    this.lastOutcome = null
  }

  /** Gọi 1 lần ở main.js. */
  init({ tokenLogin, adminService, onSessionExpired }) {
    this.tokenLogin = tokenLogin
    this.adminService = adminService
    this.onSessionExpired = onSessionExpired
  }

  /** Đã init xong chưa (có đủ thứ để gọi server làm mới token). */
  isReady() {
    return Boolean(this.tokenLogin && this.adminService)
  }

  /**
   * Bắt đầu chạy ngầm. Idempotent: gọi nhiều lần vẫn chỉ đăng ký 1 bộ listener.
   * Gọi lại khi đã chạy thì chỉ hẹn lại giờ (vd. vừa đăng nhập lại, token mới).
   */
  start() {
    if (!this.tokenLogin) return
    this.expiring = false
    if (this.started) {
      this.schedule()
      return
    }
    this.started = true
    document.addEventListener('visibilitychange', this.handleWake)
    window.addEventListener('online', this.handleWake)
    window.addEventListener('storage', this.handleStorage) // tab khác vừa làm mới -> hẹn lại giờ
    this.schedule()
  }

  /** Gọi khi đăng xuất / hết phiên. */
  stop() {
    clearTimeout(this.timer)
    clearTimeout(this.retryTimer)
    this.retryCount = 0
    if (!this.started) return
    this.started = false
    document.removeEventListener('visibilitychange', this.handleWake)
    window.removeEventListener('online', this.handleWake)
    window.removeEventListener('storage', this.handleStorage)
  }

  // ---------- Hẹn giờ ----------

  handleWake = () => {
    if (document.visibilityState === 'hidden') return
    this.tick()
  }

  handleStorage = () => this.schedule()

  getExpMs() {
    return decodeExpMs(this.tokenLogin.getAccessTokenFromStorage() || '')
  }

  /** Còn hạn nhưng đã tới lúc làm mới (hoặc đã quá hạn)? */
  isDue() {
    const exp = this.getExpMs()
    return exp !== null && exp - Date.now() <= REFRESH_BEFORE_MS
  }

  tick() {
    if (this.isDue()) this.refreshNow()
    else this.schedule()
  }

  schedule() {
    clearTimeout(this.timer)
    if (!this.started) return

    const exp = this.getExpMs()
    const delay = exp === null
        ? FALLBACK_INTERVAL_MS
        : Math.max(exp - Date.now() - REFRESH_BEFORE_MS, MIN_DELAY_MS)

    this.timer = setTimeout(() => this.tick(), Math.min(delay, MAX_TIMEOUT_MS))
  }

  // ---------- Làm mới ----------

  /**
   * Làm mới ngay. Nhiều nơi gọi cùng lúc vẫn chỉ chạy 1 lần.
   * @param staleToken access token vừa bị server từ chối (401). Nếu storage đã có token KHÁC
   *                   (tab khác làm mới rồi) thì dùng luôn, không gọi server nữa.
   * @param options.expireOnReject mặc định true: refresh token bị từ chối thì gọi onSessionExpired (về /login).
   *                   Truyền false ở luồng tự đăng nhập lúc khởi động, vì luồng đó tự xử lý "chưa đăng nhập".
   * @returns {Promise<boolean>} true = đã có access token mới dùng được. Lý do khi false: xem `lastOutcome`.
   */
  async refreshNow(staleToken = null, { expireOnReject = true } = {}) {
    if (!this.isReady()) return false

    if (!this.inflight) {
      this.inflight = this.runExclusive(() => this.doRefresh(staleToken))
          .then((outcome) => {
            this.lastOutcome = outcome
            this.applyOutcome(outcome)
            return outcome
          })
          .finally(() => { this.inflight = null })
    }

    const outcome = await this.inflight
    if (outcome === REFRESH_OUTCOME.REJECTED && expireOnReject) this.expire()
    return outcome === REFRESH_OUTCOME.OK
  }

  /** Web Locks: chỉ 1 tab được gọi refresh tại một thời điểm (refresh token bị xoay vòng, dùng lại lần hai sẽ 401). */
  runExclusive(fn) {
    return navigator.locks ? navigator.locks.request(LOCK_NAME, fn) : fn()
  }

  /** @returns {Promise<'OK'|'REJECTED'|'TRANSIENT'>} */
  async doRefresh(staleToken) {
    const storedAccess = this.tokenLogin.getAccessTokenFromStorage()
    const exp = this.getExpMs()
    const changed = staleToken ? storedAccess !== staleToken : false
    const usable = exp !== null && !this.isDue()

    // Another tab may already have rotated the HttpOnly cookie and installed a fresh in-memory access token.
    if (storedAccess && changed && usable) return REFRESH_OUTCOME.OK

    // The refresh credential is an HttpOnly cookie and is intentionally unreadable to JavaScript.
    // The server atomically consumes/rotates it and returns only a short-lived access token.
    return this.adminService.refreshTokens(this.tokenLogin)
  }

  applyOutcome(outcome) {
    if (outcome === REFRESH_OUTCOME.OK) {
      this.retryCount = 0
      clearTimeout(this.retryTimer)
      this.schedule()
      return
    }

    if (outcome === REFRESH_OUTCOME.TRANSIENT) {
      // Lỗi mạng / server tạm thời: giữ phiên, thử lại sau
      const delay = RETRY_DELAYS_MS[Math.min(this.retryCount++, RETRY_DELAYS_MS.length - 1)]
      clearTimeout(this.retryTimer)
      this.retryTimer = setTimeout(() => this.refreshNow(), delay)
    }
  }

  expire() {
    if (this.expiring) return
    this.expiring = true
    this.stop()
    this.onSessionExpired?.()
  }
}

export const silentRefresher = new SilentTokenRefresher()

/**
 * Bọc 1 request kiểu fetch: nếu 401 -> làm mới ngầm rồi gửi lại ĐÚNG 1 lần.
 * `sendRequest` phải đọc access token mới nhất mỗi lần được gọi, ví dụ:
 *
 *   const res = await retryOn401(() => fetch(url, {
 *     headers: { Authorization: `Bearer ${tokenLogin.getAccessTokenFromStorage()}` },
 *   }), tokenLogin)
 *
 * Với axios dùng SpringConfigHeader.getAPIClientWithSilentRefresh() (đã gắn sẵn interceptor).
 * KHÔNG bọc chính API login / refresh bằng hàm này (tránh lặp vô hạn).
 */
export async function retryOn401(sendRequest, tokenLogin) {
  const staleToken = tokenLogin.getAccessTokenFromStorage()
  let response = await sendRequest()
  if (response.status !== 401) return response

  const refreshed = await silentRefresher.refreshNow(staleToken)
  if (!refreshed) return response // phiên đã hết hoặc đang lỗi mạng: trả 401 gốc cho nơi gọi xử lý

  response = await sendRequest()
  return response
}
