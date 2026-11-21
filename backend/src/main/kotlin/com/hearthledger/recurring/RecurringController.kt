package com.hearthledger.recurring

import com.hearthledger.account.AccountRepository
import com.hearthledger.category.CategoryRepository
import com.hearthledger.common.Commitment
import com.hearthledger.common.Necessity
import com.hearthledger.common.badRequest
import com.hearthledger.common.notFound
import com.hearthledger.family.CurrentUser
import com.hearthledger.txn.TxnType
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Pattern
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.time.LocalDate

data class RecurringRequest(
    val type: TxnType,
    @field:DecimalMin("0.0001") val amount: BigDecimal,
    @field:Pattern(regexp = "[A-Za-z]{3}") val currency: String,
    val frequency: Frequency,
    val startDate: LocalDate,
    val endDate: LocalDate? = null,
    val accountId: Long? = null,
    val categoryId: Long? = null,
    val commitment: Commitment? = null,
    val necessity: Necessity? = null,
    val note: String? = null,
    val active: Boolean = true,
)

@RestController
@RequestMapping("/api/v1/recurring-rules")
class RecurringController(
    private val rules: RecurringRuleRepository,
    private val service: RecurringService,
    private val accounts: AccountRepository,
    private val categories: CategoryRepository,
    private val current: CurrentUser,
) {
    @GetMapping
    fun list() = rules.findByFamilyId(current.familyId()).filter { it.loanId == null }.sortedBy { it.startDate }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody r: RecurringRequest): RecurringRule {
        val m = current.requireWriter()
        validate(r, m.familyId)
        val rule = rules.save(
            RecurringRule(
                familyId = m.familyId, userId = m.userId, type = r.type, amount = r.amount, currency = r.currency.uppercase(),
                accountId = r.accountId, categoryId = r.categoryId, note = r.note, frequency = r.frequency, startDate = r.startDate,
                endDate = r.endDate, commitment = commitmentFor(r), necessity = if (r.type == TxnType.EXPENSE) r.necessity else null,
            ),
        )
        service.generateDue(rule)
        return rules.findById(rule.id!!).orElseThrow()
    }

    @PutMapping("/{id}")
    fun update(@PathVariable id: Long, @Valid @RequestBody r: RecurringRequest): RecurringRule {
        val m = current.requireWriter()
        val existing = rules.findByIdAndFamilyId(id, m.familyId)?.takeIf { it.loanId == null } ?: throw notFound("Recurring rule")
        validate(r, m.familyId)
        val saved = rules.save(
            existing.copy(
                type = r.type, amount = r.amount, currency = r.currency.uppercase(), accountId = r.accountId, categoryId = r.categoryId,
                note = r.note, frequency = r.frequency, startDate = r.startDate, endDate = r.endDate, active = r.active,
                // Occurrences derive from start date + frequency; restart so none are skipped (inserts are idempotent).
                nextIndex = if (existing.startDate != r.startDate || existing.frequency != r.frequency) 0 else existing.nextIndex,
                commitment = commitmentFor(r), necessity = if (r.type == TxnType.EXPENSE) r.necessity else null,
            ),
        )
        if (saved.active) service.generateDue(saved)
        return rules.findById(id).orElseThrow()
    }

    /** Stops future generation; already generated transactions are kept. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: Long) {
        val m = current.requireWriter()
        rules.delete(rules.findByIdAndFamilyId(id, m.familyId)?.takeIf { it.loanId == null } ?: throw notFound("Recurring rule"))
    }

    private fun commitmentFor(r: RecurringRequest) = if (r.type == TxnType.EXPENSE) r.commitment ?: Commitment.RECURRING else null

    private fun validate(r: RecurringRequest, familyId: Long) {
        if (r.type == TxnType.TRANSFER) throw badRequest("RECURRING_TYPE", "Only income and expenses can recur")
        if (r.endDate != null && r.endDate.isBefore(r.startDate)) throw badRequest("RECURRING_DATES", "End date is before start date")
        r.accountId?.let { accounts.findByIdAndFamilyId(it, familyId) ?: throw notFound("Account") }
        r.categoryId?.let { categories.findByIdAndFamilyId(it, familyId) ?: throw notFound("Category") }
    }
}
