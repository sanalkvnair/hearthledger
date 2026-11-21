package com.hearthledger.family

import com.hearthledger.user.AppUserRepository
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.http.HttpStatus
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.*
import java.time.Instant
import jakarta.validation.Valid

data class FamilyDto(
    val id: Long, val name: String, val baseCurrency: String, val cycleStartDay: Int,
    val bufferMonths: Int, val bufferLookbackMonths: Int, val llmImportEnabled: Boolean,
    val adjustPeriodToBusinessDay: Boolean, val holidayCountry: String,
)

fun Family.toDto() = FamilyDto(id!!, name, baseCurrency, cycleStartDay, bufferMonths, bufferLookbackMonths, llmImportEnabled, adjustPeriodToBusinessDay, holidayCountry.trim())

data class MeDto(
    val userId: Long, val email: String, val name: String, val avatarUrl: String?,
    val locale: String, val theme: String, val role: Role?, val family: FamilyDto?,
    val defaultExpenseAccountId: Long?, val defaultIncomeAccountId: Long?, val defaultSavingsSourceAccountId: Long?,
)

data class CreateFamilyRequest(
    @field:NotBlank val name: String,
    @field:Pattern(regexp = "[A-Za-z]{3}") val baseCurrency: String,
    @field:Min(1) @field:Max(31) val cycleStartDay: Int = 1,
)

data class UpdateFamilyRequest(
    @field:NotBlank val name: String,
    @field:Min(1) @field:Max(31) val cycleStartDay: Int,
    @field:Min(1) @field:Max(24) val bufferMonths: Int,
    @field:Min(1) @field:Max(24) val bufferLookbackMonths: Int,
    val llmImportEnabled: Boolean,
    val adjustPeriodToBusinessDay: Boolean = false,
    @field:Pattern(regexp = "[A-Za-z]{2}") val holidayCountry: String = "SE",
)

data class DefaultAccountsRequest(val expenseAccountId: Long? = null, val incomeAccountId: Long? = null, val savingsSourceAccountId: Long? = null)

data class PreferencesRequest(@field:Pattern(regexp = "en|sv") val locale: String, @field:Pattern(regexp = "light|dark|auto") val theme: String)
data class InviteRequest(val email: String? = null, val role: Role = Role.MEMBER)
data class InvitationDto(val code: String, val email: String?, val role: Role, val expiresAt: Instant)
data class MemberDto(val userId: Long, val name: String, val email: String, val avatarUrl: String?, val role: Role)
data class RoleRequest(val role: Role)

@RestController
@RequestMapping("/api/v1")
@Validated
class FamilyController(
    private val service: FamilyService,
    private val current: CurrentUser,
    private val users: AppUserRepository,
    private val families: FamilyRepository,
    private val accounts: com.hearthledger.account.AccountRepository,
    private val jdbc: JdbcClient,
) {
    @GetMapping("/me")
    fun me(): MeDto {
        val user = users.findById(current.userId()).orElseThrow()
        val membership = current.membershipOrNull()
        val family = membership?.let { families.findById(it.familyId).orElseThrow().toDto() }
        return MeDto(user.id!!, user.email, user.name, user.avatarUrl, user.locale, user.theme, membership?.role, family,
            user.defaultExpenseAccountId, user.defaultIncomeAccountId, user.defaultSavingsSourceAccountId)
    }

    @PutMapping("/me/preferences")
    fun preferences(@Valid @RequestBody r: PreferencesRequest) {
        val user = users.findById(current.userId()).orElseThrow()
        users.save(user.copy(locale = r.locale, theme = r.theme))
    }

    @PutMapping("/me/default-accounts")
    fun defaultAccounts(@Valid @RequestBody r: DefaultAccountsRequest) {
        val familyId = current.familyId()
        listOfNotNull(r.expenseAccountId, r.incomeAccountId, r.savingsSourceAccountId).forEach {
            accounts.findByIdAndFamilyId(it, familyId) ?: throw com.hearthledger.common.notFound("Account")
        }
        users.save(
            users.findById(current.userId()).orElseThrow().copy(
                defaultExpenseAccountId = r.expenseAccountId, defaultIncomeAccountId = r.incomeAccountId,
                defaultSavingsSourceAccountId = r.savingsSourceAccountId,
            ),
        )
    }

    @PostMapping("/families")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody r: CreateFamilyRequest) = service.create(r.name, r.baseCurrency, r.cycleStartDay).toDto()

    @GetMapping("/family")
    fun get() = service.get().toDto()

    @PutMapping("/family")
    fun update(@Valid @RequestBody r: UpdateFamilyRequest) =
        service.updateSettings(r.name, r.cycleStartDay, r.bufferMonths, r.bufferLookbackMonths, r.llmImportEnabled, r.adjustPeriodToBusinessDay, r.holidayCountry).toDto()

    @GetMapping("/family/members")
    fun members(): List<MemberDto> =
        jdbc.sql(
            """SELECT u.id, u.name, u.email, u.avatar_url, m.role FROM family_member m
               JOIN app_user u ON u.id = m.user_id WHERE m.family_id = :f ORDER BY m.joined_at""",
        ).param("f", current.familyId()).query { rs, _ ->
            MemberDto(rs.getLong("id"), rs.getString("name"), rs.getString("email"), rs.getString("avatar_url"), Role.valueOf(rs.getString("role")))
        }.list()

    @PutMapping("/family/members/{userId}/role")
    fun changeRole(@PathVariable userId: Long, @RequestBody r: RoleRequest) = service.changeRole(userId, r.role)

    @DeleteMapping("/family/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun remove(@PathVariable userId: Long) = service.removeMember(userId)

    @PostMapping("/family/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    fun invite(@RequestBody r: InviteRequest) = service.invite(r.email, r.role).let { InvitationDto(it.code, it.email, it.role, it.expiresAt) }

    @GetMapping("/family/invitations")
    fun invitations() = service.pendingInvitations().map { InvitationDto(it.code, it.email, it.role, it.expiresAt) }

    @PostMapping("/invitations/{code}/accept")
    fun accept(@PathVariable code: String) = service.accept(code).toDto()
}
