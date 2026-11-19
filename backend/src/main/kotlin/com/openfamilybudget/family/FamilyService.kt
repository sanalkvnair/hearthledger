package com.openfamilybudget.family

import com.openfamilybudget.common.ApiException
import com.openfamilybudget.common.badRequest
import com.openfamilybudget.common.forbidden
import com.openfamilybudget.common.notFound
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.time.Instant
import java.time.temporal.ChronoUnit

@Service
class FamilyService(
    private val families: FamilyRepository,
    private val members: FamilyMemberRepository,
    private val invitations: InvitationRepository,
    private val users: com.openfamilybudget.user.AppUserRepository,
    private val events: ApplicationEventPublisher,
    private val holidays: com.openfamilybudget.period.HolidayCalendars,
    private val current: CurrentUser,
) {
    private val random = SecureRandom()

    @Transactional
    fun create(name: String, baseCurrency: String, cycleStartDay: Int): Family {
        val userId = current.userId()
        if (members.findByUserId(userId) != null) {
            throw ApiException(HttpStatus.CONFLICT, "ALREADY_IN_FAMILY", "You already belong to a family")
        }
        val family = families.save(Family(name = name, baseCurrency = baseCurrency.uppercase(), cycleStartDay = cycleStartDay))
        members.save(FamilyMember(familyId = family.id!!, userId = userId, role = Role.OWNER))
        events.publishEvent(FamilyCreated(family.id))
        return family
    }

    fun get(): Family = families.findById(current.familyId()).orElseThrow { notFound("Family") }

    @Transactional
    fun updateSettings(
        name: String, cycleStartDay: Int, bufferMonths: Int, lookback: Int, llm: Boolean,
        adjustToBusinessDay: Boolean, holidayCountry: String,
    ): Family {
        current.requireAdmin()
        val country = holidayCountry.uppercase()
        if (!holidays.isSupported(country)) throw badRequest("HOLIDAY_COUNTRY", "No public holiday calendar for $country")
        return families.save(
            get().copy(
                name = name, cycleStartDay = cycleStartDay, bufferMonths = bufferMonths,
                bufferLookbackMonths = lookback, llmImportEnabled = llm,
                adjustPeriodToBusinessDay = adjustToBusinessDay, holidayCountry = country,
            ),
        )
    }

    @Transactional
    fun invite(email: String?, role: Role): Invitation {
        val admin = current.requireAdmin()
        if (role == Role.OWNER) throw badRequest("INVALID_ROLE", "Cannot invite as owner")
        val code = ByteArray(9).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        return invitations.save(
            Invitation(
                familyId = admin.familyId, code = code, email = email?.lowercase(), role = role,
                expiresAt = Instant.now().plus(7, ChronoUnit.DAYS), createdBy = admin.userId,
            ),
        )
    }

    fun pendingInvitations(): List<Invitation> {
        val admin = current.requireAdmin()
        return invitations.findByFamilyIdAndAcceptedByIsNull(admin.familyId).filter { it.expiresAt.isAfter(Instant.now()) }
    }

    @Transactional
    fun accept(code: String): Family {
        val userId = current.userId()
        if (members.findByUserId(userId) != null) {
            throw ApiException(HttpStatus.CONFLICT, "ALREADY_IN_FAMILY", "You already belong to a family")
        }
        val inv = invitations.findByCode(code) ?: throw notFound("Invitation")
        if (inv.acceptedBy != null || inv.expiresAt.isBefore(Instant.now())) {
            throw ApiException(HttpStatus.GONE, "INVITATION_EXPIRED", "Invitation is no longer valid")
        }
        if (inv.email != null && !inv.email.equals(users.findById(userId).orElseThrow().email, ignoreCase = true)) {
            throw ApiException(HttpStatus.FORBIDDEN, "INVITATION_EMAIL", "This invitation was issued for a different email address")
        }
        if (!invitations.markAccepted(inv.id!!, userId)) {
            throw ApiException(HttpStatus.GONE, "INVITATION_EXPIRED", "Invitation is no longer valid")
        }
        members.save(FamilyMember(familyId = inv.familyId, userId = userId, role = inv.role))
        return families.findById(inv.familyId).orElseThrow()
    }

    @Transactional
    fun changeRole(userId: Long, role: Role) {
        val admin = current.requireAdmin()
        if (role == Role.OWNER) throw badRequest("INVALID_ROLE", "Ownership transfer is not supported")
        val target = members.findByFamilyIdAndUserId(admin.familyId, userId) ?: throw notFound("Member")
        if (target.role == Role.OWNER) throw forbidden("The owner's role cannot be changed")
        if (target.role == Role.ADMIN && admin.role != Role.OWNER) throw forbidden("Only the owner can change an admin")
        members.save(target.copy(role = role))
    }

    @Transactional
    fun removeMember(userId: Long) {
        val caller = current.membership()
        val target = members.findByFamilyIdAndUserId(caller.familyId, userId) ?: throw notFound("Member")
        if (target.role == Role.OWNER) throw forbidden("The owner cannot be removed")
        if (userId != caller.userId) {
            current.requireAdmin()
            if (target.role == Role.ADMIN && caller.role != Role.OWNER) throw forbidden("Only the owner can remove an admin")
        }
        members.delete(target)
    }
}
