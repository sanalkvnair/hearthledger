package com.hearthledger.account

import com.hearthledger.common.badRequest
import com.hearthledger.common.notFound
import com.hearthledger.family.CurrentUser
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import org.springframework.http.HttpStatus
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal

data class AccountRequest(
    @field:NotBlank val name: String,
    val type: AccountType,
    @field:Pattern(regexp = "[A-Za-z]{3}") val currency: String,
    val openingBalance: BigDecimal = BigDecimal.ZERO,
    val institution: String? = null,
    /** Defaults to PRIVATE when [ownerIds]/[ownerUserId] name a single member, JOINT for several, FAMILY for none. */
    val ownership: Ownership? = null,
    val ownerIds: List<Long>? = null,
    /** Shorthand for a single owner. */
    val ownerUserId: Long? = null,
    val archived: Boolean = false,
    @field:Min(1) @field:Max(31) val statementDay: Int? = null,
    @field:Min(1) @field:Max(31) val dueDay: Int? = null,
)

data class AccountDto(
    val id: Long, val familyId: Long, val name: String, val type: AccountType, val currency: String, val openingBalance: BigDecimal,
    val institution: String?, val archived: Boolean, val ownership: Ownership, val ownerIds: List<Long>,
    val statementDay: Int?, val dueDay: Int?,
)

@RestController
@RequestMapping("/api/v1/accounts")
class AccountController(private val repo: AccountRepository, private val owners: AccountOwners, private val current: CurrentUser) {

    @GetMapping
    fun list(): List<AccountDto> {
        val familyId = current.familyId()
        val byAccount = owners.byAccount(familyId)
        return repo.findByFamilyId(familyId).sortedBy { it.name }.map { it.toDto(byAccount[it.id] ?: emptyList()) }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    fun create(@Valid @RequestBody r: AccountRequest): AccountDto {
        val familyId = current.requireWriter().familyId
        val (ownership, ownerIds) = resolveOwners(r, familyId)
        val saved = repo.save(
            Account(
                familyId = familyId, name = r.name, type = r.type, currency = r.currency.uppercase(), openingBalance = r.openingBalance,
                institution = r.institution, ownership = ownership, statementDay = cardDay(r, r.statementDay), dueDay = cardDay(r, r.dueDay),
            ),
        )
        owners.set(saved.id!!, ownerIds)
        return saved.toDto(ownerIds)
    }

    @PutMapping("/{id}")
    @Transactional
    fun update(@PathVariable id: Long, @Valid @RequestBody r: AccountRequest): AccountDto {
        val familyId = current.requireWriter().familyId
        val a = repo.findByIdAndFamilyId(id, familyId) ?: throw notFound("Account")
        val (ownership, ownerIds) = resolveOwners(r, familyId)
        val saved = repo.save(
            a.copy(
                name = r.name, type = r.type, currency = r.currency.uppercase(), openingBalance = r.openingBalance,
                institution = r.institution, archived = r.archived, ownership = ownership,
                statementDay = cardDay(r, r.statementDay), dueDay = cardDay(r, r.dueDay),
            ),
        )
        owners.set(id, ownerIds)
        return saved.toDto(ownerIds)
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: Long) {
        val familyId = current.requireAdmin().familyId
        repo.delete(repo.findByIdAndFamilyId(id, familyId) ?: throw notFound("Account"))
    }

    private fun cardDay(r: AccountRequest, day: Int?): Int? =
        if (r.type == AccountType.CREDIT_CARD) day else if (day != null) throw badRequest("CARD_ONLY", "Statement and due day only apply to credit cards") else null

    private fun resolveOwners(r: AccountRequest, familyId: Long): Pair<Ownership, List<Long>> {
        val ids = (r.ownerIds ?: listOfNotNull(r.ownerUserId)).distinct()
        ids.forEach { current.requireFamilyMember(it, familyId) }
        val ownership = r.ownership ?: when (ids.size) { 0 -> Ownership.FAMILY; 1 -> Ownership.PRIVATE; else -> Ownership.JOINT }
        when (ownership) {
            Ownership.PRIVATE -> if (ids.size != 1) throw badRequest("OWNERS", "A private account needs exactly one owner")
            Ownership.JOINT -> if (ids.size < 2) throw badRequest("OWNERS", "A joint account needs at least two owners")
            Ownership.FAMILY -> if (ids.isNotEmpty()) throw badRequest("OWNERS", "A family account has no individual owners")
        }
        return ownership to ids
    }

    private fun Account.toDto(ownerIds: List<Long>) =
        AccountDto(id!!, familyId, name, type, currency, openingBalance, institution, archived, ownership, ownerIds, statementDay, dueDay)
}
