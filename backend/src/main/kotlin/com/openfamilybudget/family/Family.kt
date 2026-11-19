package com.openfamilybudget.family

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.CrudRepository
import java.time.Instant

enum class Role { OWNER, ADMIN, MEMBER, VIEWER }

@Table("family")
data class Family(
    @Id val id: Long? = null,
    val name: String,
    val baseCurrency: String,
    val cycleStartDay: Int = 1,
    val bufferMonths: Int = 3,
    val bufferLookbackMonths: Int = 3,
    val llmImportEnabled: Boolean = false,
    val adjustPeriodToBusinessDay: Boolean = false,
    val holidayCountry: String = "SE",
    val createdAt: Instant = Instant.now(),
)

@Table("family_member")
data class FamilyMember(
    @Id val id: Long? = null,
    val familyId: Long,
    val userId: Long,
    val role: Role,
    val joinedAt: Instant = Instant.now(),
)

@Table("invitation")
data class Invitation(
    @Id val id: Long? = null,
    val familyId: Long,
    val code: String,
    val email: String? = null,
    val role: Role,
    val expiresAt: Instant,
    val createdBy: Long,
    val acceptedBy: Long? = null,
)

interface FamilyRepository : CrudRepository<Family, Long>

interface FamilyMemberRepository : CrudRepository<FamilyMember, Long> {
    fun findByUserId(userId: Long): FamilyMember?
    fun findByFamilyId(familyId: Long): List<FamilyMember>
    fun findByFamilyIdAndUserId(familyId: Long, userId: Long): FamilyMember?
}

interface InvitationRepository : CrudRepository<Invitation, Long> {
    fun findByCode(code: String): Invitation?

    @org.springframework.data.jdbc.repository.query.Modifying
    @org.springframework.data.jdbc.repository.query.Query("UPDATE invitation SET accepted_by = :user WHERE id = :id AND accepted_by IS NULL")
    fun markAccepted(@org.springframework.data.repository.query.Param("id") id: Long, @org.springframework.data.repository.query.Param("user") user: Long): Boolean
    fun findByFamilyIdAndAcceptedByIsNull(familyId: Long): List<Invitation>
}

/** Published when a family is created; other modules react (e.g. seeding default categories). */
data class FamilyCreated(val familyId: Long)
