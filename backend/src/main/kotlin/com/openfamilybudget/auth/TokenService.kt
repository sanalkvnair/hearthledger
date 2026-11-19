package com.openfamilybudget.auth

import com.openfamilybudget.user.AppUser
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

@Service
class TokenService(
    private val encoder: JwtEncoder,
    private val props: AppProperties,
    private val jdbc: JdbcClient,
) {
    private val random = SecureRandom()

    fun accessToken(user: AppUser): String {
        val now = Instant.now()
        val claims = JwtClaimsSet.builder()
            .issuer("open-family-budget")
            .subject(user.id.toString())
            .issuedAt(now)
            .expiresAt(now.plus(props.jwt.accessTtl))
            .claim("email", user.email)
            .claim("name", user.name)
            .build()
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).tokenValue
    }

    fun newRefreshToken(userId: Long): String {
        val raw = ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        jdbc.sql("INSERT INTO refresh_token (user_id, token_hash, expires_at) VALUES (:u, :h, :e)")
            .param("u", userId).param("h", hash(raw)).param("e", java.sql.Timestamp.from(Instant.now().plus(props.jwt.refreshTtl)))
            .update()
        return raw
    }

    /** Rotates the refresh token: returns the user id if valid, revoking the presented token. */
    fun consumeRefreshToken(raw: String): Long? {
        return jdbc.sql(
            "UPDATE refresh_token SET revoked = TRUE WHERE token_hash = :h AND revoked = FALSE AND expires_at > now() RETURNING user_id",
        ).param("h", hash(raw)).query(Long::class.java).optional().orElse(null)
    }

    fun revoke(raw: String) {
        jdbc.sql("UPDATE refresh_token SET revoked = TRUE WHERE token_hash = :h").param("h", hash(raw)).update()
    }

    private fun hash(raw: String) =
        MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
}
