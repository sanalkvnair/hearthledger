package com.openfamilybudget.audit

import com.openfamilybudget.family.CurrentUser
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Instant

private val MUTATING = setOf("POST", "PUT", "PATCH", "DELETE")
private val IGNORED = listOf("/api/v1/auth/", "/preview", "/inspect")

/** Records every successful state-changing API call (who, what, when) per family. Runs inside the security chain. */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
class AuditFilter(private val jdbc: JdbcClient) : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun shouldNotFilter(request: HttpServletRequest) =
        request.method !in MUTATING || !request.requestURI.startsWith("/api/v1/") || IGNORED.any { request.requestURI.contains(it) }

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        chain.doFilter(request, response)
        if (response.status !in 200..299) return
        val userId = SecurityContextHolder.getContext().authentication?.name?.toLongOrNull() ?: return
        try {
            val parts = request.requestURI.removePrefix("/api/v1/").split('/').filter { it.isNotEmpty() }
            val familyId = jdbc.sql("SELECT family_id FROM family_member WHERE user_id = :u").param("u", userId).query(Long::class.java).optional().orElse(null)
                ?: return
            jdbc.sql("INSERT INTO audit_log (family_id, user_id, method, path, entity, entity_id, status) VALUES (:f, :u, :m, :p, :e, :i, :s)")
                .param("f", familyId).param("u", userId).param("m", request.method).param("p", request.requestURI)
                .param("e", parts.firstOrNull() ?: "").param("i", parts.firstNotNullOfOrNull { it.toLongOrNull() }).param("s", response.status).update()
        } catch (e: Exception) {
            log.warn("Audit logging failed: {}", e.message)
        }
    }
}

data class AuditEntry(val id: Long, val userId: Long?, val userName: String?, val method: String, val path: String, val entity: String, val entityId: Long?, val status: Int, val at: Instant)

@RestController
@RequestMapping("/api/v1/audit")
class AuditController(private val jdbc: JdbcClient, private val current: CurrentUser) {
    @GetMapping
    fun list(@RequestParam(defaultValue = "100") limit: Int, @RequestParam(defaultValue = "0") offset: Int): List<AuditEntry> {
        val m = current.requireAdmin()
        return jdbc.sql(
            """SELECT a.id, a.user_id, u.name, a.method, a.path, a.entity, a.entity_id, a.status, a.at FROM audit_log a
               LEFT JOIN app_user u ON u.id = a.user_id WHERE a.family_id = :f ORDER BY a.at DESC, a.id DESC LIMIT :l OFFSET :o""",
        ).param("f", m.familyId).param("l", limit.coerceIn(1, 500)).param("o", offset.coerceAtLeast(0)).query { rs, _ ->
            AuditEntry(rs.getLong(1), rs.getObject(2) as Long?, rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getObject(7) as Long?, rs.getInt(8), rs.getTimestamp(9).toInstant())
        }.list()
    }
}
