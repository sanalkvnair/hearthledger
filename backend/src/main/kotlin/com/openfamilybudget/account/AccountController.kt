package com.openfamilybudget.account

import com.openfamilybudget.common.notFound
import com.openfamilybudget.family.CurrentUser
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal

data class AccountRequest(
    @field:NotBlank val name: String,
    val type: AccountType,
    @field:Pattern(regexp = "[A-Za-z]{3}") val currency: String,
    val openingBalance: BigDecimal = BigDecimal.ZERO,
    val institution: String? = null,
    /** Null = shared by the whole family; otherwise the owning member's user id. */
    val ownerUserId: Long? = null,
    val archived: Boolean = false,
)

@RestController
@RequestMapping("/api/v1/accounts")
class AccountController(private val repo: AccountRepository, private val current: CurrentUser) {

    @GetMapping
    fun list() = repo.findByFamilyId(current.familyId()).sortedBy { it.name }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody r: AccountRequest): Account {
        val familyId = current.requireWriter().familyId
        current.requireFamilyMember(r.ownerUserId, familyId)
        return repo.save(
            Account(
                familyId = familyId, ownerUserId = r.ownerUserId, name = r.name, type = r.type,
                currency = r.currency.uppercase(), openingBalance = r.openingBalance, institution = r.institution,
            ),
        )
    }

    @PutMapping("/{id}")
    fun update(@PathVariable id: Long, @Valid @RequestBody r: AccountRequest): Account {
        val familyId = current.requireWriter().familyId
        val a = repo.findByIdAndFamilyId(id, familyId) ?: throw notFound("Account")
        current.requireFamilyMember(r.ownerUserId, familyId)
        return repo.save(
            a.copy(
                name = r.name, type = r.type, currency = r.currency.uppercase(), openingBalance = r.openingBalance,
                institution = r.institution, ownerUserId = r.ownerUserId, archived = r.archived,
            ),
        )
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: Long) {
        val familyId = current.requireAdmin().familyId
        repo.delete(repo.findByIdAndFamilyId(id, familyId) ?: throw notFound("Account"))
    }
}
