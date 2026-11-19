package com.openfamilybudget.loan

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.CrudRepository
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate

enum class LoanTerm { SHORT_TERM, LONG_TERM }

@Table("loan")
data class Loan(
    @Id val id: Long? = null,
    val familyId: Long,
    val ownerUserId: Long? = null,
    val name: String,
    val lender: String? = null,
    val currency: String,
    val principal: BigDecimal,
    val interestRate: BigDecimal,
    val tenureMonths: Int,
    val emi: BigDecimal,
    val startDate: LocalDate,
    val term: LoanTerm,
    val categoryId: Long? = null,
    val accountId: Long? = null,
    val recurringRuleId: Long? = null,
    val active: Boolean = true,
)

interface LoanRepository : CrudRepository<Loan, Long> {
    fun findByFamilyId(familyId: Long): List<Loan>
    fun findByIdAndFamilyId(id: Long, familyId: Long): Loan?
}

data class ScheduleRow(val number: Int, val date: LocalDate, val payment: BigDecimal, val interest: BigDecimal, val principal: BigDecimal, val balance: BigDecimal)

object Amortization {
    private val mc = MathContext(20)

    private fun monthlyRate(annualPercent: BigDecimal) = annualPercent.divide(BigDecimal(1200), mc)

    fun emi(principal: BigDecimal, annualPercent: BigDecimal, months: Int): BigDecimal {
        val r = monthlyRate(annualPercent)
        if (r.signum() == 0) return principal.divide(BigDecimal(months), 4, RoundingMode.HALF_UP)
        val factor = BigDecimal.ONE.add(r).pow(months, mc)
        return principal.multiply(r).multiply(factor).divide(factor.subtract(BigDecimal.ONE), 4, RoundingMode.HALF_UP)
    }

    /** Outstanding principal after [paid] instalments of [emi]. */
    fun balanceAfter(loan: Loan, paid: Int): BigDecimal {
        var balance = loan.principal
        val r = monthlyRate(loan.interestRate)
        repeat(paid.coerceIn(0, loan.tenureMonths)) {
            balance = balance.add(balance.multiply(r, mc)).subtract(loan.emi)
        }
        return if (paid >= loan.tenureMonths || balance.signum() < 0) BigDecimal.ZERO else balance.setScale(4, RoundingMode.HALF_UP)
    }

    fun schedule(loan: Loan): List<ScheduleRow> {
        val r = monthlyRate(loan.interestRate)
        var balance = loan.principal
        return (1..loan.tenureMonths).map { n ->
            val interest = balance.multiply(r, mc).setScale(4, RoundingMode.HALF_UP)
            // the last instalment absorbs rounding so the balance ends at exactly zero
            val payment = if (n == loan.tenureMonths) balance.add(interest) else loan.emi
            val principalPart = payment.subtract(interest)
            balance = balance.subtract(principalPart).max(BigDecimal.ZERO)
            ScheduleRow(n, loan.startDate.plusMonths((n - 1).toLong()), payment, interest, principalPart, balance)
        }
    }
}
