package com.openfamilybudget.loan

import com.openfamilybudget.common.badRequest
import com.openfamilybudget.common.money
import com.openfamilybudget.common.notFound
import com.openfamilybudget.family.CurrentUser
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
    /** Loan amount (for a balloon loan: the price before the down payment). */
    @field:DecimalMin("0.01") val principal: BigDecimal,
    /** Current annual interest rate in percent; later changes go through /rates. */
    @field:DecimalMin("0") val interestRate: BigDecimal = BigDecimal.ZERO,
    /** Required for ANNUITY and BALLOON_ANNUITY; ignored otherwise. For a running loan: remaining payments. */
    @field:Min(1) val tenureMonths: Int? = null,
    /** ANNUITY only; calculated from principal, rate and tenure when omitted. */
    val emi: BigDecimal? = null,
    /** Date of the first payment to schedule (the next payment for a loan that is already running). */
    val startDate: LocalDate,
    val loanType: LoanType = LoanType.ANNUITY,
    /** FIXED_AMORTIZATION: yearly repayment as % of the original principal. */
    @field:DecimalMin("0") val amortizationRate: BigDecimal? = null,
    @field:DecimalMin("0") val balloonAmount: BigDecimal = BigDecimal.ZERO,
    @field:DecimalMin("0") val downPayment: BigDecimal = BigDecimal.ZERO,
    @field:DecimalMin("0") val setupFee: BigDecimal = BigDecimal.ZERO,
    @field:DecimalMin("0") val monthlyFee: BigDecimal = BigDecimal.ZERO,
    /** Outstanding balance before the first scheduled payment, for loans that are already running. */
    @field:DecimalMin("0.01") val openingBalance: BigDecimal? = null,
    /** Defaults to short-term for tenures up to 24 months, otherwise long-term. */
    val term: LoanTerm? = null,
    val ownerUserId: Long? = null,
    val categoryId: Long? = null,
    val accountId: Long? = null,
)

data class RateRequest(val effectiveFrom: LocalDate, @field:DecimalMin("0") val annualRate: BigDecimal)

data class LoanDto(
    val id: Long, val name: String, val lender: String?, val currency: String, val principal: BigDecimal,
    /** Rate that applies today. */
    val interestRate: BigDecimal,
    val tenureMonths: Int?, val emi: BigDecimal, val startDate: LocalDate, val endDate: LocalDate?, val term: LoanTerm,
    val loanType: LoanType, val amortizationRate: BigDecimal?, val balloonAmount: BigDecimal, val downPayment: BigDecimal,
    val setupFee: BigDecimal, val monthlyFee: BigDecimal, val openingBalance: BigDecimal?,
    /** Annual rate in percent including fees, from the IRR of all cash flows. */
    val effectiveRate: BigDecimal?,
    val ownerUserId: Long?, val paidInstalments: Int, val remainingInstalments: Int?, val remainingBalance: BigDecimal,
    val nextPayment: BigDecimal?, val active: Boolean, val rates: List<RateChangeDto>,
)

data class LoanPreview(
    val financed: BigDecimal, val firstPayment: BigDecimal, val lastPayment: BigDecimal, val payments: Int,
    val totalInterest: BigDecimal, val totalFees: BigDecimal, val totalCost: BigDecimal, val balloon: BigDecimal,
    val effectiveRate: BigDecimal?, val openEnded: Boolean, val firstRows: List<ScheduleRow>,
)

@RestController
@RequestMapping("/api/v1/loans")
class LoanController(
    private val loans: LoanRepository,
    private val service: LoanService,
    private val accounts: com.openfamilybudget.account.AccountRepository,
    private val categories: com.openfamilybudget.category.CategoryRepository,
    private val jdbc: JdbcClient,
    private val current: CurrentUser,
) {
    @GetMapping
    fun list(): List<LoanDto> = loans.findByFamilyId(current.familyId()).sortedBy { it.startDate }.map { it.toDto() }

    @GetMapping("/{id}/schedule")
    fun schedule(@PathVariable id: Long) = service.schedule(loans.findByIdAndFamilyId(id, current.familyId()) ?: throw notFound("Loan"))

    /** Calculates a loan without saving it. */
    @PostMapping("/preview")
    fun preview(@Valid @RequestBody r: LoanRequest): LoanPreview {
        val loan = build(r, current.familyId(), current.userId())
        val rows = LoanCalculator.schedule(loan)
        val openEnded = loan.loanType == LoanType.INTEREST_ONLY
        return LoanPreview(
            LoanCalculator.financed(loan).money(), rows.first().total.money(), rows.last().total.money(), rows.size,
            rows.sumOf { it.interest }.money(), rows.sumOf { it.fee }.money(), rows.sumOf { it.total }.money(),
            (if (loan.loanType == LoanType.BALLOON_ANNUITY) loan.balloonAmount else BigDecimal.ZERO).money(),
            LoanCalculator.effectiveRate(loan, rows), openEnded, rows.take(12),
        )
    }

    /** Creates the loan and books its payments from the schedule (past ones immediately, future ones as they fall due). */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    fun create(@Valid @RequestBody r: LoanRequest): LoanDto {
        val m = current.requireWriter()
        val loan = loans.save(build(r, m.familyId, m.userId))
        service.generateDue(loan)
        return loans.findById(loan.id!!).orElseThrow().toDto()
    }

    @PostMapping("/{id}/rates")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    fun addRate(@PathVariable id: Long, @Valid @RequestBody r: RateRequest): LoanDto {
        val loan = loans.findByIdAndFamilyId(id, current.requireWriter().familyId) ?: throw notFound("Loan")
        jdbc.sql(
            """INSERT INTO loan_rate (loan_id, effective_from, annual_rate) VALUES (:l, :d, :r)
               ON CONFLICT (loan_id, effective_from) DO UPDATE SET annual_rate = EXCLUDED.annual_rate""",
        ).param("l", id).param("d", r.effectiveFrom).param("r", r.annualRate).update()
        service.generateDue(loan)
        return loan.toDto()
    }

    @DeleteMapping("/{id}/rates/{rateId}")
    @Transactional
    fun deleteRate(@PathVariable id: Long, @PathVariable rateId: Long): LoanDto {
        val loan = loans.findByIdAndFamilyId(id, current.requireWriter().familyId) ?: throw notFound("Loan")
        jdbc.sql("DELETE FROM loan_rate WHERE id = :r AND loan_id = :l").param("r", rateId).param("l", id).update()
        return loan.toDto()
    }

    /** Closing a loan stops automatic payment bookings; history is kept. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    fun close(@PathVariable id: Long) {
        val loan = loans.findByIdAndFamilyId(id, current.requireWriter().familyId) ?: throw notFound("Loan")
        loans.save(loan.copy(active = false))
    }

    private fun build(r: LoanRequest, familyId: Long, userId: Long): Loan {
        current.requireFamilyMember(r.ownerUserId, familyId)
        r.accountId?.let { accounts.findByIdAndFamilyId(it, familyId) ?: throw notFound("Account") }
        r.categoryId?.let { categories.findByIdAndFamilyId(it, familyId) ?: throw notFound("Category") }

        val tenure = when (r.loanType) {
            LoanType.ANNUITY, LoanType.BALLOON_ANNUITY -> r.tenureMonths ?: throw badRequest("TENURE", "Tenure is required for this loan type")
            else -> null
        }
        when (r.loanType) {
            LoanType.FIXED_AMORTIZATION ->
                if (r.amortizationRate == null || r.amortizationRate.signum() <= 0) throw badRequest("AMORTIZATION", "Set the yearly amortization percentage")
            LoanType.INTEREST_ONLY ->
                if (r.interestRate.signum() <= 0) throw badRequest("INTEREST", "An interest-only loan needs an interest rate")
            else -> {}
        }
        if (r.loanType != LoanType.FIXED_AMORTIZATION && r.amortizationRate != null) throw badRequest("AMORTIZATION", "Amortization percentage only applies to fixed amortization loans")
        val balloonLoan = r.loanType == LoanType.BALLOON_ANNUITY
        if (!balloonLoan && r.balloonAmount.signum() != 0) throw badRequest("BALLOON", "Balloon amount only applies to balloon annuity loans")
        val financed = r.openingBalance ?: (r.principal - r.downPayment)
        if (financed.signum() <= 0) throw badRequest("PRINCIPAL", "The financed amount must be positive")
        if (balloonLoan && r.balloonAmount >= financed) throw badRequest("BALLOON", "The balloon amount must be lower than the financed amount")

        val provisional = Loan(
            familyId = familyId, ownerUserId = r.ownerUserId, name = r.name, lender = r.lender, currency = r.currency.uppercase(),
            principal = r.principal, interestRate = r.interestRate, tenureMonths = tenure, emi = BigDecimal.ZERO, startDate = r.startDate,
            term = r.term ?: if (tenure != null && tenure <= 24) LoanTerm.SHORT_TERM else LoanTerm.LONG_TERM,
            categoryId = r.categoryId, accountId = r.accountId, loanType = r.loanType, amortizationRate = r.amortizationRate,
            balloonAmount = if (balloonLoan) r.balloonAmount else BigDecimal.ZERO, downPayment = r.downPayment, setupFee = r.setupFee,
            monthlyFee = r.monthlyFee, openingBalance = r.openingBalance, createdBy = userId,
        )
        val emi = when (r.loanType) {
            LoanType.ANNUITY -> r.emi ?: Amortization.emi(financed, r.interestRate, tenure!!)
            else -> LoanCalculator.schedule(provisional).firstOrNull()?.payment ?: BigDecimal.ZERO
        }
        if (emi.signum() <= 0) throw badRequest("EMI", "The calculated payment is zero; check the amounts and rate")
        return provisional.copy(emi = emi)
    }

    private fun Loan.toDto(): LoanDto {
        val changes = service.rateChanges(id!!)
        val rows = LoanCalculator.schedule(this, changes.map { RateChange(it.effectiveFrom, it.annualRate) }, LocalDate.now())
        val paid = generatedThrough
        val openEnded = loanType == LoanType.INTEREST_ONLY
        return LoanDto(
            id, name, lender, currency, principal, LoanCalculator.rateAt(this, changes.map { RateChange(it.effectiveFrom, it.annualRate) }, LocalDate.now()),
            if (openEnded) null else rows.size, emi, startDate, if (openEnded) null else rows.lastOrNull()?.date, term, loanType, amortizationRate,
            balloonAmount, downPayment, setupFee, monthlyFee, openingBalance, LoanCalculator.effectiveRate(this, rows), ownerUserId, paid,
            if (openEnded) null else (rows.size - paid).coerceAtLeast(0), LoanCalculator.balanceAfter(this, rows, paid).money(),
            service.nextMonthlyPayment(this, rows, paid)?.money(), active, changes,
        )
    }
}
