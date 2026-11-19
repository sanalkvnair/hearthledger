package com.openfamilybudget.auth

import com.nimbusds.jose.jwk.source.ImmutableSecret
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher
import javax.crypto.spec.SecretKeySpec

const val ACCESS_COOKIE = "ofb_access"
const val REFRESH_COOKIE = "ofb_refresh"

@Configuration
class SecurityConfig(private val props: AppProperties) {

    private val key by lazy {
        require(props.jwt.secret.toByteArray().size >= 32 && !props.jwt.secret.startsWith("dev-only")) {
            "app.jwt.secret (JWT_SECRET) must be set to a random value of at least 32 bytes, e.g. `openssl rand -hex 32`"
        }
        SecretKeySpec(props.jwt.secret.toByteArray(), "HmacSHA256")
    }

    @Bean
    fun jwtEncoder(): JwtEncoder = NimbusJwtEncoder(ImmutableSecret(key))

    @Bean
    fun jwtDecoder(): JwtDecoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build()

    /** Accepts `Authorization: Bearer` (mobile/API clients) or the httpOnly access cookie (web). */
    @Bean
    fun bearerTokenResolver(): BearerTokenResolver {
        val header = DefaultBearerTokenResolver()
        return BearerTokenResolver { req ->
            header.resolve(req) ?: req.cookies?.firstOrNull { it.name == ACCESS_COOKIE }?.value
        }
    }

    @Bean
    fun filterChain(http: HttpSecurity, success: OAuthSuccessHandler, resolver: BearerTokenResolver): SecurityFilterChain {
        http
            .csrf(AbstractHttpConfigurer<*, *>::disable) // cookies are SameSite=Lax; API is JSON-only
            .authorizeHttpRequests {
                it.requestMatchers(
                    "/actuator/health/**", "/oauth2/**", "/login/oauth2/**",
                    "/api/v1/auth/refresh", "/api/v1/auth/logout",
                    "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html",
                ).permitAll()
                it.anyRequest().authenticated()
            }
            .headers {
                it.referrerPolicy { r -> r.policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN) }
                if (props.cookieSecure) it.httpStrictTransportSecurity { h -> h.maxAgeInSeconds(31536000).includeSubDomains(true) }
            }
            .oauth2Login { it.successHandler(success) }
            .oauth2ResourceServer { it.jwt(Customizer.withDefaults()).bearerTokenResolver(resolver) }
            .exceptionHandling {
                it.defaultAuthenticationEntryPointFor(
                    HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                    PathPatternRequestMatcher.withDefaults().matcher("/api/**"),
                )
            }
        return http.build()
    }
}
