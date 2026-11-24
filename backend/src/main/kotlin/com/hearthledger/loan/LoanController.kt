package com.hearthledger.loan

import com.hearthledger.common.badRequest
import com.hearthledger.common.money
import com.hearthledger.common.notFound
import com.hearthledger.family.CurrentUser
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
    /** Required for ANNUITY and BALLOON_ANNUITY; optional for FIXED_AMORTIZATION and INTEREST_ONLY (the loan then ends after this many payments). For a running loan: remaining payments. */
    @field:Min(1) val tenureMonths: Int? = null,
    /** ANNUITY only; calculated from principal, rate and tenure when omitted. */
    val emi: BigDecimal? = null,
    /** Date of the first payment to schedule (the next payment for a loan that is already running). */
    val startDate: LocalDate,
    val loanType: LoanType = LoanType.ANNUITY,
    /** FIXED_AMORTIZATION: yearly repayment as % of the original principal. */
    @field:DecimalMin("0") val amortizationRate: BigDecimal? = null,
    /** FIXED_AMORTIZATION: monthly repayment as an amount; alternative to [amortizationRate]. */
    @field:DecimalMin("0") val amortizationAmount: BigDecimal? = null,
    /** Informational: when the loan was taken out. [startDate] is the first payment. */
    val disbursedOn: LocalDate? = null,
    /** Informational: until when [interestRate] is fixed; empty means flexible. */
    val rateFixedUntil: LocalDate? = null,
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

data class RateRequest(val effectiveFrom: LocalDate, @field:DecimalMin("0") val annualRate: BigDecimal, val fixedUntil: LocalDate? = null)

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
    /** The term as entered (months); [tenureMonths] is the number of scheduled payments. */
    val termMonths: Int?, val initialRate: BigDecimal, val initialEffectiveRate: BigDecimal, val amortizationAmount: BigDecimal?,
    val monthlyAmortization: BigDecimal?, val disbursedOn: LocalDate?, val rateFixedUntil: LocalDate?,
    /** When the rate that applies today stops being fixed; null means flexible. */
    val currentRateFixedUntil: LocalDate?,
    val accountId: Long?, val categoryId: Long?, val nextPaymentDate: LocalDate?, val nextInterest: BigDecimal?, val nextPrincipal: BigDecimal?,
)

data class LoanPreview(
    val financed: BigDecimal, val firstPayment: BigDecimal, val lastPayment: BigDecimal, val payments: Int,
    val totalInterest: BigDecimal, val totalFees: BigDecimal, val totalCost: BigDecimal, val balloon: BigDecimal,
    val effectiveRate: BigDecimal?, val openEnded: Boolean, val firstRows: List<ScheduleRow>,
    val firstDate: LocalDate, val lastDate: LocalDate?, val firstInterest: BigDecimal, val firstPrincipal: BigDecimal,
    /** Monthly repayment of a fixed amortization loan. */
    val monthlyAmortization: BigDecimal?,
)

@RestController
@RequestMapping("/api/v1/loans")
class LoanController(
    private val loans: LoanRepository,
    private val service: LoanService,
    private val accounts: com.hearthledger.account.AccountRepository,
    private val categories: com.hearthledger.category.CategoryRepository,
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
        val openEnded = loan.loanType == LoanType.INTEREST_ONLY && loan.tenureMonths == null
        return LoanPreview(
            LoanCalculator.financed(loan).money(), rows.first().total.money(), rows.last().total.money(), rows.size,
            rows.sumOf { it.interest }.money(), rows.sumOf { it.fee }.money(), rows.sumOf { it.total }.money(),
            (if (loan.loanType == LoanType.BALLOON_ANNUITY) loan.balloonAmount else BigDecimal.ZERO).money(),
            LoanCalculator.effectiveRate(loan, rows), openEnded, rows.take(12),
            rows.first().date, if (openEnded) null else rows.last().date, rows.first().interest.money(), rows.first().principal.money(),
            if (loan.loanType == LoanType.FIXED_AMORTIZATION) LoanCalculator.monthlyAmortization(loan).money() else null,
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
            """INSERT INTO loan_rate (loan_id, effective_from, annual_rate, fixed_until) VALUES (:l, :d, :r, :f)
               ON CONFLICT (loan_id, effective_from) DO UPDATE SET annual_rate = EXCLUDED.annual_rate, fixed_until = EXCLUDED.fixed_until""",
        ).param("l", id).param("d", r.effectiveFrom).param("r", r.annualRate).param("f", r.fixedUntil).update()
        return service.resync(loan).toDto()
    }

    @DeleteMapping("/{id}/rates/{rateId}")
    @Transactional
    fun deleteRate(@PathVariable id: Long, @PathVariable rateId: Long): LoanDto {
        val loan = loans.findByIdAndFamilyId(id, current.requireWriter().familyId) ?: throw notFound("Loan")
        jdbc.sql("DELETE FROM loan_rate WHERE id = :r AND loan_id = :l").param("r", rateId).param("l", id).update()
        return service.resync(loan).toDto()
    }

    /** Replaces all loan settings and recalculates the payments that were already booked. */
    @PutMapping("/{id}")
    @Transactional
    fun update(@PathVariable id: Long, @Valid @RequestBody r: LoanRequest): LoanDto {
        val m = current.requireWriter()
        val existing = loans.findByIdAndFamilyId(id, m.familyId) ?: throw notFound("Loan")
        val rebuilt = build(r, m.familyId, m.userId).copy(
            id = existing.id, active = existing.active, generatedThrough = existing.generatedThrough, createdBy = existing.createdBy,
        )
        return service.resync(loans.save(rebuilt)).toDto()
    }

    /** Closing a loan stops automatic payment bookings; history is kept. */
    @PostMapping("/{id}/close")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    fun close(@PathVariable id: Long) {
        val loan = loans.findByIdAndFamilyId(id, current.requireWriter().familyId) ?: throw notFound("Loan")
        loans.save(loan.copy(active = false))
    }

    @PostMapping("/{id}/reopen")
    @Transactional
    fun reopen(@PathVariable id: Long): LoanDto {
        val loan = loans.findByIdAndFamilyId(id, current.requireWriter().familyId) ?: throw notFound("Loan")
        return service.resync(loans.save(loan.copy(active = true))).toDto()
    }

    /** Deletes the loan with its rate changes. Booked payments are deleted too, or kept as plain expenses when [deletePayments] is false. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    fun delete(@PathVariable id: Long, @RequestParam(defaultValue = "false") deletePayments: Boolean) {
        val loan = loans.findByIdAndFamilyId(id, current.requireWriter().familyId) ?: throw notFound("Loan")
        if (deletePayments) jdbc.sql("DELETE FROM txn WHERE loan_id = :l AND loan_schedule_no IS NOT NULL").param("l", id).update()
        else jdbc.sql("UPDATE txn SET loan_schedule_no = NULL WHERE loan_id = :l").param("l", id).update()
        loans.delete(loan)
    }

    private fun build(r: LoanRequest, familyId: Long, userId: Long): Loan {
        current.requireFamilyMember(r.ownerUserId, familyId)
        r.accountId?.let { accounts.findByIdAndFamilyId(it, familyId) ?: throw notFound("Account") }
        r.categoryId?.let { categories.findByIdAndFamilyId(it, familyId) ?: throw notFound("Category") }

        val tenure = when (r.loanType) {
            LoanType.ANNUITY, LoanType.BALLOON_ANNUITY -> r.tenureMonths ?: throw badRequest("TENURE", "Tenure is required for this loan type")
            else -> r.tenureMonths
        }
        val hasAmount = r.amortizationAmount != null && r.amortizationAmount.signum() > 0
        val hasPercent = r.amortizationRate != null && r.amortizationRate.signum() > 0
        when (r.loanType) {
            LoanType.FIXED_AMORTIZATION -> {
                if (hasAmount && hasPercent) throw badRequest("AMORTIZATION", "Set either a monthly amortization amount or a yearly percentage, not both")
                if (!hasAmount && !hasPercent && tenure == null) throw badRequest("AMORTIZATION", "Set the amortization (amount or yearly percentage) or the term")
            }
            LoanType.INTEREST_ONLY ->
                if (r.interestRate.signum() <= 0) throw badRequest("INTEREST", "An interest-only loan needs an interest rate")
            else -> {}
        }
        if (r.loanType != LoanType.FIXED_AMORTIZATION && (r.amortizationRate != null || r.amortizationAmount != null)) throw badRequest("AMORTIZATION", "Amortization percentage only applies to fixed amortization loans")
        val balloonLoan = r.loanType == LoanType.BALLOON_ANNUITY
        if (!balloonLoan && r.balloonAmount.signum() != 0) throw badRequest("BALLOON", "Balloon amount only applies to balloon annuity loans")
        val financed = r.openingBalance ?: (r.principal - r.downPayment)
        if (financed.signum() <= 0) throw badRequest("PRINCIPAL", "The financed amount must be positive")
        if (balloonLoan && r.balloonAmount >= financed) throw badRequest("BALLOON", "The balloon amount must be lower than the financed amount")

        val provisional = Loan(
            familyId = familyId, ownerUserId = r.ownerUserId, name = r.name, lender = r.lender, currency = r.currency.uppercase(),
            principal = r.principal, interestRate = r.interestRate, tenureMonths = tenure, emi = BigDecimal.ZERO, startDate = r.startDate,
            term = r.term ?: if (tenure != null && tenure <= 24) LoanTerm.SHORT_TERM else LoanTerm.LONG_TERM,
            categoryId = r.categoryId, accountId = r.accountId, loanType = r.loanType, amortizationRate = if (hasPercent) r.amortizationRate else null,
            amortizationAmount = if (hasAmount) r.amortizationAmount else null, disbursedOn = r.disbursedOn, rateFixedUntil = r.rateFixedUntil,
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
        val today = LocalDate.now()
        val rateChanges = changes.map { RateChange(it.effectiveFrom, it.annualRate) }
        val rows = LoanCalculator.schedule(this, rateChanges, today)
        val paid = generatedThrough
        val openEnded = loanType == LoanType.INTEREST_ONLY && tenureMonths == null
        val rate = LoanCalculator.rateAt(this, rateChanges, today)
        val applied = changes.lastOrNull { !it.effectiveFrom.isAfter(today) }
        val next = rows.getOrNull(paid)
        val hasFees = setupFee.signum() != 0 || monthlyFee.signum() != 0
        return LoanDto(
            id, name, lender, currency, principal, rate,
            if (openEnded) null else rows.size, emi, startDate, if (openEnded) null else rows.lastOrNull()?.date, term, loanType, amortizationRate,
            balloonAmount, downPayment, setupFee, monthlyFee, openingBalance,
            if (hasFees) LoanCalculator.effectiveRate(this, rows) else Amortization.effectiveOf(rate),
            ownerUserId, paid, if (openEnded) null else (rows.size - paid).coerceAtLeast(0), LoanCalculator.balanceAfter(this, rows, paid).money(),
            service.nextMonthlyPayment(this, rows, paid)?.money(), active, changes,
            tenureMonths, interestRate, Amortization.effectiveOf(interestRate), amortizationAmount,
            if (loanType == LoanType.FIXED_AMORTIZATION) LoanCalculator.monthlyAmortization(this).money() else null,
            disbursedOn, rateFixedUntil, if (applied != null) applied.fixedUntil else rateFixedUntil,
            accountId, categoryId, next?.date, next?.interest?.money(), next?.principal?.money(),
        )
    }
}
