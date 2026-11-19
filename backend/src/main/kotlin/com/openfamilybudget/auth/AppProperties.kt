package com.openfamilybudget.auth

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("app")
data class AppProperties(
    val frontendUrl: String,
    val cookieSecure: Boolean,
    val jwt: Jwt,
    val llm: Llm = Llm(),
) {
    data class Llm(val baseUrl: String = "https://api.openai.com/v1", val apiKey: String = "", val model: String = "gpt-4o-mini")
    data class Jwt(val secret: String, val accessTtl: Duration, val refreshTtl: Duration)
}
