package com.openfamilybudget.auth

import com.openfamilybudget.common.ApiException
import com.openfamilybudget.user.AppUserRepository
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class RefreshRequest(val refreshToken: String? = null)
data class TokenResponse(val accessToken: String, val refreshToken: String)

@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val tokens: TokenService,
    private val users: AppUserRepository,
    private val cookies: AuthCookies,
) {
    /** Web clients rely on the refresh cookie; mobile clients may send the token in the body. */
    @PostMapping("/refresh")
    fun refresh(req: HttpServletRequest, res: HttpServletResponse, @RequestBody(required = false) body: RefreshRequest?): TokenResponse {
        val raw = body?.refreshToken ?: req.cookies?.firstOrNull { it.name == REFRESH_COOKIE }?.value
            ?: throw ApiException(HttpStatus.UNAUTHORIZED, "NO_REFRESH_TOKEN", "Missing refresh token")
        val userId = tokens.consumeRefreshToken(raw)
            ?: throw ApiException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", "Invalid refresh token")
        val user = users.findById(userId).orElseThrow()
        return cookies.issue(res, user, tokens)
    }

    @PostMapping("/logout")
    fun logout(req: HttpServletRequest, res: HttpServletResponse) {
        req.cookies?.firstOrNull { it.name == REFRESH_COOKIE }?.value?.let(tokens::revoke)
        cookies.clear(res)
    }
}
