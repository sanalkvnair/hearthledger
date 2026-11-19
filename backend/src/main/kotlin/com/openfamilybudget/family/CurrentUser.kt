package com.openfamilybudget.family

import com.openfamilybudget.common.ApiException
import com.openfamilybudget.common.forbidden
import org.springframework.http.HttpStatus
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Component

/** Resolves the caller and their family from the JWT; every family-scoped query must use [familyId]. */
@Component
class CurrentUser(private val members: FamilyMemberRepository) {

    fun userId(): Long =
        (SecurityContextHolder.getContext().authentication as? JwtAuthenticationToken)?.token?.subject?.toLong()
            ?: throw ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Not authenticated")

    fun membershipOrNull(): FamilyMember? = members.findByUserId(userId())

    fun membership(): FamilyMember =
        membershipOrNull() ?: throw ApiException(HttpStatus.CONFLICT, "NO_FAMILY", "Create or join a family first")

    fun familyId(): Long = membership().familyId

    /** Any role that may create/edit data (everyone except VIEWER). */
    fun requireWriter(): FamilyMember = membership().also { if (it.role == Role.VIEWER) throw forbidden() }

    fun requireAdmin(): FamilyMember =
        membership().also { if (it.role != Role.OWNER && it.role != Role.ADMIN) throw forbidden() }
}
