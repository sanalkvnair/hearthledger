package com.hearthledger.auth

import com.hearthledger.user.AppUser
import com.hearthledger.user.AppUserRepository
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.ResponseCookie
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.security.web.authentication.AuthenticationSuccessHandler
import org.springframework.stereotype.Component
import java.time.Duration

@Component
class OAuthSuccessHandler(
    private val users: AppUserRepository,
    private val tokens: TokenService,
    private val cookies: AuthCookies,
    private val props: AppProperties,
) : AuthenticationSuccessHandler {

    override fun onAuthenticationSuccess(req: HttpServletRequest, res: HttpServletResponse, auth: Authentication) {
        val principal = auth.principal as OAuth2User
        val sub = principal.getAttribute<String>("sub") ?: error("Missing sub")
        val email = principal.getAttribute<String>("email") ?: error("Missing email")
        val name = principal.getAttribute<String>("name") ?: email
        val picture = principal.getAttribute<String>("picture")

        val existing = users.findByGoogleSub(sub)
        val user: AppUser = users.save(
            existing?.copy(email = email, name = name, avatarUrl = picture)
                ?: AppUser(email = email, name = name, avatarUrl = picture, googleSub = sub),
        )
        cookies.issue(res, user, tokens)
        req.getSession(false)?.invalidate() // from here on the API is stateless (JWT only)
        res.sendRedirect(props.frontendUrl)
    }
}

@Component
class AuthCookies(private val props: AppProperties) {
    fun issue(res: HttpServletResponse, user: AppUser, tokens: TokenService): TokenResponse {
        val access = tokens.accessToken(user)
        val refresh = tokens.newRefreshToken(user.id!!)
        add(res, ACCESS_COOKIE, access, props.jwt.accessTtl, "/")
        add(res, REFRESH_COOKIE, refresh, props.jwt.refreshTtl, "/api/v1/auth")
        return TokenResponse(access, refresh)
    }

    fun clear(res: HttpServletResponse) {
        add(res, ACCESS_COOKIE, "", Duration.ZERO, "/")
        add(res, REFRESH_COOKIE, "", Duration.ZERO, "/api/v1/auth")
    }

    private fun add(res: HttpServletResponse, name: String, value: String, ttl: Duration, path: String) {
        val c = ResponseCookie.from(name, value).httpOnly(true).secure(props.cookieSecure)
            .sameSite("Lax").path(path).maxAge(ttl).build()
        res.addHeader("Set-Cookie", c.toString())
    }
}
