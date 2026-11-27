import './assets/main.css'
import { createApp } from 'vue'
import App from './App.vue'

// Bootstrap
import 'bootstrap/dist/css/bootstrap.css'
import 'bootstrap/dist/js/bootstrap.js'

// Vue Router & Vuetify
import { createRouter, createWebHistory } from 'vue-router';
import { createVuetify } from 'vuetify';
import 'vuetify/styles';
import 'vuetify/dist/vuetify-labs.min.css';

import RouterMgmt from '@/router/RouterMgmt.js'
import routersBeforeLogin from '@/router/routers_before_login.js'
import routersAfterLogin from '@/router/routers_after_login.js'
import { AdminService } from '@/services/spring/AdminService.js'
import { TokenLogin } from '@/model/TokenLogin.js'
import { onRemoteLogout } from '@/assets/auth-sync.js'
import { silentRefresher } from '@/services/auth/SilentTokenRefresher.js'

const LOGIN_PATH = '/login'
const NOT_FOUND_PATH = '/404-not-found'
const adminService = new AdminService()
const tokenLogin = new TokenLogin()
const routerManagement = new RouterMgmt()

function clearSessionAndGoToLogin() {
    silentRefresher.stop()
    tokenLogin.removeAccessTokenAndRefreshTokenFromStorage()
    if (window.location.pathname === LOGIN_PATH) return
    try {
        routerManagement.removePathFromLocalStorage()
        sessionStorage.clear()
    } catch (error) {
        console.error('Error clearing session:', error)
    }
    window.location.replace(LOGIN_PATH)
}

function setupSilentRefresh() {
    silentRefresher.init({
        tokenLogin,
        adminService,
        onSessionExpired: clearSessionAndGoToLogin,
    })
}

async function autoLogin() {
    try {
        // JS cannot inspect the HttpOnly refresh cookie; let the backend resume the session.
        return Boolean(await adminService.getDataToAutoLoginAccessToken(tokenLogin))
    } catch (error) {
        console.error('Auto login failed:', error)
        return false
    }
}

function resolveTargetPath(currentPath, routes, isLoggedIn) {
    const matchedRoute = routes.find((route) => route.path === currentPath)
    if (!isLoggedIn) {
        if (!matchedRoute || matchedRoute.allow === false) return LOGIN_PATH
    } else if (!matchedRoute) {
        return NOT_FOUND_PATH
    }
    if (currentPath === '/') {
        if (!isLoggedIn) return LOGIN_PATH
        return routerManagement.getPathFromLocalStorage() || routes[0]?.path || NOT_FOUND_PATH
    }
    const pathSession = routerManagement.getPathFromSessionStorage()
    const canRestoreSession = pathSession && pathSession !== currentPath
        && routes.some((route) => route.path === pathSession)
    return canRestoreSession ? pathSession : currentPath
}

function setupCrossTabLogout() {
    onRemoteLogout(clearSessionAndGoToLogin)
}

async function mountApp(routes, targetPath) {
    const app = createApp(App)
    const router = createRouter({ history: createWebHistory(), routes })
    app.use(createVuetify())
    app.use(router)
    try {
        await router.replace(targetPath)
    } catch (error) {
        console.error('Error navigating:', error)
    }
    app.mount('#app')
}

async function bootstrap() {
    setupCrossTabLogout()
    setupSilentRefresh()

    const isLoggedIn = await autoLogin()
    if (import.meta.env.DEV) console.log('Status login auto:', isLoggedIn)
    if (!isLoggedIn) routerManagement.removePathFromLocalStorage()

    const routes = isLoggedIn ? routersAfterLogin : routersBeforeLogin
    const targetPath = resolveTargetPath(window.location.pathname, routes, isLoggedIn)
    if (isLoggedIn) silentRefresher.start()
    await mountApp(routes, targetPath)
}

await bootstrap()
