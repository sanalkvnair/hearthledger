package com.hearthledger.ratelimit

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.concurrent.ConcurrentHashMap

/** Fixed-window counter per key; windows are one minute. */
class WindowCounter(private val clock: () -> Long = System::currentTimeMillis) {
    private data class Slot(val minute: Long, var count: Int)
    private val slots = ConcurrentHashMap<String, Slot>()

    fun allow(key: String, limit: Int): Boolean {
        val minute = clock() / 60_000
        if (slots.size > 10_000) slots.entries.removeIf { it.value.minute < minute }
        val slot = slots.compute(key) { _, s -> if (s == null || s.minute != minute) Slot(minute, 1) else s.also { it.count++ } }!!
        return slot.count <= limit
    }
}

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class RateLimitFilter(
    @Value("\${app.rate-limit.general:600}") private val general: Int,
    @Value("\${app.rate-limit.sensitive:30}") private val sensitive: Int,
) : OncePerRequestFilter() {
    private val counter = WindowCounter()

    /** nginx overwrites X-Forwarded-For with the real peer address, so the backend must not be exposed directly. */
    private fun client(r: HttpServletRequest) = r.getHeader("X-Forwarded-For")?.substringAfterLast(',')?.trim()?.ifEmpty { null } ?: r.remoteAddr

    override fun shouldNotFilter(request: HttpServletRequest) = request.requestURI.startsWith("/actuator")

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val uri = request.requestURI
        val isSensitive = uri.startsWith("/oauth2") || uri.startsWith("/login/oauth2") || uri.startsWith("/api/v1/auth/") ||
            uri.startsWith("/api/v1/import/") && request.method == "POST" && (uri.contains("/preview") || uri.contains("/inspect"))
        val ok = if (isSensitive) counter.allow("s:${client(request)}", sensitive) else counter.allow("g:${client(request)}", general)
        if (ok) return chain.doFilter(request, response)
        response.status = 429
        response.setHeader("Retry-After", "60")
        response.contentType = "application/problem+json"
        response.writer.write("""{"status":429,"code":"RATE_LIMITED","detail":"Too many requests, try again shortly"}""")
    }
}
