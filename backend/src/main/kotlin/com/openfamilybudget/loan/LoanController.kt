package com.openfamilybudget.loan

import com.openfamilybudget.common.Commitment
import com.openfamilybudget.common.Necessity
import com.openfamilybudget.common.notFound
import com.openfamilybudget.family.CurrentUser
import com.openfamilybudget.recurring.Frequency
import com.openfamilybudget.recurring.RecurringRule
import com.openfamilybudget.recurring.RecurringRuleRepository
import com.openfamilybudget.recurring.RecurringService
import com.openfamilybudget.txn.TxnType
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.time.LocalDate

data class LoanRequest(
    @field:NotBlank val name: String,
    val lender: String? = null,
    @field:Pattern(regexp = "[A-Za-z]{3}") val currency: String,
    @field:DecimalMin("0.01") val principal: BigDecimal,
    @field:DecimalMin("0") val interestRate: BigDecimal = BigDecimal.ZERO,
    @field:Min(1) val tenureMonths: Int,
    /** Calculated from principal, rate and tenure when omitted. */
    val emi: BigDecimal? = null,
    /** Date of the first instalment. */
    val startDate: LocalDate,
    /** Defaults to short-term for tenures up to 24 months, otherwise long-term. */
    val term: LoanTerm? = null,
    val ownerUserId: Long? = null,
    val categoryId: Long? = null,
    val accountId: Long? = null,
)

data class LoanDto(
    val id: Long, val name: String, val lender: String?, val currency: String, val principal: BigDecimal, val interestRate: BigDecimal,
    val tenureMonths: Int, val emi: BigDecimal, val startDate: LocalDate, val endDate: LocalDate, val term: LoanTerm,
    val ownerUserId: Long?, val paidInstalments: Int, val remainingInstalments: Int, val remainingBalance: BigDecimal, val active: Boolean,
)

@RestController
@RequestMapping("/api/v1/loans")
class LoanController(
    private val loans: LoanRepository,
    private val accounts: com.openfamilybudget.account.AccountRepository,
    private val categories: com.openfamilybudget.category.CategoryRepository,
    private val rules: RecurringRuleRepository,
    private val recurring: RecurringService,
    private val jdbc: JdbcClient,
    private val current: CurrentUser,
) {
    @GetMapping
    fun list(): List<LoanDto> {
        val familyId = current.familyId()
        val paid = jdbc.sql("SELECT loan_id, count(*) FROM txn WHERE family_id = :f AND loan_id IS NOT NULL GROUP BY loan_id")
            .param("f", familyId).query { rs, _ -> rs.getLong(1) to rs.getInt(2) }.list().toMap()
        return loans.findByFamilyId(familyId).sortedBy { it.startDate }.map { it.toDto(paid[it.id] ?: 0) }
    }

    @GetMapping("/{id}/schedule")
    fun schedule(@PathVariable id: Long) = Amortization.schedule(loans.findByIdAndFamilyId(id, current.familyId()) ?: throw notFound("Loan"))

    /** Creates the loan and a monthly recurring rule that books each EMI automatically. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    fun create(@Valid @RequestBody r: LoanRequest): LoanDto {
        val m = current.requireWriter()
        current.requireFamilyMember(r.ownerUserId, m.familyId)
        r.accountId?.let { accounts.findByIdAndFamilyId(it, m.familyId) ?: throw notFound("Account") }
        r.categoryId?.let { categories.findByIdAndFamilyId(it, m.familyId) ?: throw notFound("Category") }
        val term = r.term ?: if (r.tenureMonths <= 24) LoanTerm.SHORT_TERM else LoanTerm.LONG_TERM
        val emi = r.emi ?: Amortization.emi(r.principal, r.interestRate, r.tenureMonths)
        val loan = loans.save(
            Loan(
                familyId = m.familyId, ownerUserId = r.ownerUserId, name = r.name, lender = r.lender, currency = r.currency.uppercase(),
                principal = r.principal, interestRate = r.interestRate, tenureMonths = r.tenureMonths, emi = emi,
                startDate = r.startDate, term = term, categoryId = r.categoryId, accountId = r.accountId,
            ),
        )
        val rule = rules.save(
            RecurringRule(
                familyId = m.familyId, userId = r.ownerUserId ?: m.userId, type = TxnType.EXPENSE, amount = emi, currency = loan.currency,
                accountId = r.accountId, categoryId = r.categoryId, note = r.name, frequency = Frequency.MONTHLY, startDate = r.startDate,
                endDate = r.startDate.plusMonths((r.tenureMonths - 1).toLong()), loanId = loan.id,
                commitment = if (term == LoanTerm.SHORT_TERM) Commitment.SHORT_TERM_LOAN else Commitment.LONG_TERM_LOAN,
                necessity = Necessity.ESSENTIAL,
            ),
        )
        val saved = loans.save(loan.copy(recurringRuleId = rule.id))
        recurring.generateDue(rule)
        return saved.toDto(jdbc.sql("SELECT count(*) FROM txn WHERE loan_id = :l").param("l", saved.id).query(Int::class.java).single())
    }

    /** Closing a loan stops automatic EMI bookings; history is kept. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    fun close(@PathVariable id: Long) {
        val loan = loans.findByIdAndFamilyId(id, current.requireWriter().familyId) ?: throw notFound("Loan")
        loan.recurringRuleId?.let { rid -> rules.findById(rid).ifPresent { rules.save(it.copy(active = false)) } }
        loans.save(loan.copy(active = false))
    }

    private fun Loan.toDto(paid: Int) = LoanDto(
        id!!, name, lender, currency, principal, interestRate, tenureMonths, emi, startDate, startDate.plusMonths((tenureMonths - 1).toLong()),
        term, ownerUserId, paid, (tenureMonths - paid).coerceAtLeast(0), Amortization.balanceAfter(this, paid), active,
    )
}
