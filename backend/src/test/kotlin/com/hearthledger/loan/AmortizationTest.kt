package com.hearthledger.loan

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AmortizationTest {
    private fun loan(rate: String, months: Int, principal: String = "12000") = Loan(
        familyId = 1, name = "L", currency = "EUR", principal = BigDecimal(principal), interestRate = BigDecimal(rate),
        tenureMonths = months, emi = Amortization.emi(BigDecimal(principal), BigDecimal(rate), months),
        startDate = LocalDate.of(2026, 1, 31), term = LoanTerm.SHORT_TERM,
    )

    @Test
    fun `zero interest splits principal evenly`() {
        assertEquals(0, BigDecimal("1000").compareTo(Amortization.emi(BigDecimal(12000), BigDecimal.ZERO, 12)))
    }

    @Test
    fun `standard EMI formula`() {
        // 100000 at 12% p.a. over 12 months -> 8884.88
        assertEquals(BigDecimal("8884.8789"), Amortization.emi(BigDecimal(100000), BigDecimal(12), 12))
    }

    @Test
    fun `schedule ends at zero and balance matches schedule`() {
        val l = loan("9.5", 24)
        val schedule = LoanCalculator.schedule(l)
        assertEquals(24, schedule.size)
        assertEquals(0, schedule.last().balance.signum())
        assertEquals(schedule[5].balance, LoanCalculator.balanceAfter(l, schedule, 6))
        assertEquals(0, LoanCalculator.balanceAfter(l, schedule, 24).signum())
    }

    private fun d(s: String) = LocalDate.parse(s)

    @Test
    fun `fixed amortization repays a share of the original principal each month`() {
        // 2% of 1,200,000 per year = 2,000 per month; interest 2.75% on the remaining balance
        val l = Loan(
            familyId = 1, name = "H", currency = "SEK", principal = BigDecimal(1200000), interestRate = BigDecimal("2.75"),
            emi = BigDecimal.ONE, startDate = d("2026-01-25"), term = LoanTerm.LONG_TERM, loanType = LoanType.FIXED_AMORTIZATION,
            amortizationRate = BigDecimal(2),
        )
        val rows = LoanCalculator.schedule(l)
        assertEquals(0, BigDecimal(2000).compareTo(rows[0].principal))
        assertEquals(0, BigDecimal(2750).compareTo(rows[0].interest))
        assertEquals(0, BigDecimal(1198000).compareTo(rows[0].balance))
        assertTrue(rows[1].payment < rows[0].payment, "payment decreases as interest falls")
        assertEquals(600, rows.size)
        assertEquals(0, rows.last().balance.signum())
    }

    @Test
    fun `interest only never repays principal`() {
        val l = Loan(
            familyId = 1, name = "H", currency = "SEK", principal = BigDecimal(1000000), interestRate = BigDecimal("2.75"),
            emi = BigDecimal.ONE, startDate = d("2026-01-25"), term = LoanTerm.LONG_TERM, loanType = LoanType.INTEREST_ONLY,
        )
        val rows = LoanCalculator.schedule(l)
        assertTrue(rows.all { it.principal.signum() == 0 && it.balance.compareTo(BigDecimal(1000000)) == 0 })
        assertEquals(0, BigDecimal("2291.6667").compareTo(rows[0].payment))
        assertEquals(null, LoanCalculator.effectiveRate(l, rows))
    }

    @Test
    fun `balloon annuity leaves the residual for the last payment and IRR includes fees`() {
        val l = Loan(
            familyId = 1, name = "Car", currency = "SEK", principal = BigDecimal(300000), interestRate = BigDecimal("5"),
            tenureMonths = 36, emi = BigDecimal.ONE, startDate = d("2026-01-25"), term = LoanTerm.LONG_TERM, loanType = LoanType.BALLOON_ANNUITY,
            balloonAmount = BigDecimal(120000), downPayment = BigDecimal(60000), setupFee = BigDecimal(600), monthlyFee = BigDecimal(35),
        )
        val rows = LoanCalculator.schedule(l)
        assertEquals(36, rows.size)
        assertEquals(0, rows.last().balance.signum())
        // before the final payment, the balance equals the balloon
        assertTrue((rows[34].balance - BigDecimal(120000)).abs() < BigDecimal("5000"), "balance before last payment was ${rows[34].balance}")
        val effective = LoanCalculator.effectiveRate(l, rows)!!
        assertTrue(effective > BigDecimal("5.1162"), "fees push the effective rate above the nominal 5.12% annual equivalent, was $effective")
    }

    @Test
    fun `IRR matches the closed form for a plain annuity`() {
        val l = loan("12", 12, "100000")
        val effective = LoanCalculator.effectiveRate(l, LoanCalculator.schedule(l))!!
        assertEquals(0, BigDecimal("12.6825").compareTo(effective.setScale(4, java.math.RoundingMode.HALF_UP)), "was $effective")
    }

    @Test
    fun `rate change recalculates the instalment over the remaining term`() {
        val l = loan("6", 24)
        val rows = LoanCalculator.schedule(l, listOf(RateChange(d("2027-01-31"), BigDecimal(12))))
        assertTrue(rows[13].payment > rows[11].payment)
        assertEquals(0, rows.last().balance.signum())
    }

    @Test
    fun `running loan starts from the outstanding balance`() {
        val l = loan("6", 12, "12000").copy(openingBalance = BigDecimal(6000))
        val rows = LoanCalculator.schedule(l.copy(emi = Amortization.emi(BigDecimal(6000), BigDecimal(6), 12)))
        assertEquals(12, rows.size)
        assertTrue(rows[0].principal + rows[0].interest < BigDecimal(600))
    }

    private fun reference(type: LoanType, tenure: Int?, amount: String? = null) = Loan(
        familyId = 1, name = "Ref", currency = "SEK", principal = BigDecimal(1105000), interestRate = BigDecimal("4.94"), tenureMonths = tenure,
        emi = BigDecimal.ONE, startDate = d("2023-12-28"), term = LoanTerm.LONG_TERM, loanType = type, amortizationAmount = amount?.let(::BigDecimal),
    )

    private val referenceRates = listOf(
        RateChange(d("2024-10-29"), BigDecimal("4.13")), RateChange(d("2025-02-04"), BigDecimal("3.13")), RateChange(d("2025-08-04"), BigDecimal("2.93")),
        RateChange(d("2025-10-29"), BigDecimal("2.65")), RateChange(d("2026-05-05"), BigDecimal("2.75")),
    )

    @Test
    fun `fixed amortization with a monthly amount reproduces a real loan`() {
        val l = reference(LoanType.FIXED_AMORTIZATION, 360, "3683")
        val row = LoanCalculator.schedule(l, referenceRates)[35] // 2026-11-28
        assertEquals(d("2026-11-28"), row.date)
        assertEquals(0, BigDecimal("2237").compareTo(row.interest.setScale(0, java.math.RoundingMode.HALF_UP)))
        assertEquals(0, BigDecimal("3683").compareTo(row.principal))
        assertEquals(0, BigDecimal("5920").compareTo(row.payment.setScale(0, java.math.RoundingMode.HALF_UP)))
    }

    @Test
    fun `fixed amortization defaults to principal divided by the term and ends at the term`() {
        val l = reference(LoanType.FIXED_AMORTIZATION, 300)
        val rows = LoanCalculator.schedule(l, referenceRates)
        assertEquals(300, rows.size)
        assertEquals(0, BigDecimal("3683.3333").compareTo(LoanCalculator.monthlyAmortization(l)))
        assertEquals(0, rows.last().balance.signum())
    }

    @Test
    fun `interest only with a term repays the principal in the last row`() {
        val l = reference(LoanType.INTEREST_ONLY, 720)
        val rows = LoanCalculator.schedule(l, referenceRates)
        assertEquals(720, rows.size)
        assertEquals(0, BigDecimal("2532").compareTo(rows[35].interest.setScale(0, java.math.RoundingMode.HALF_UP)))
        assertEquals(0, rows[35].principal.signum())
        assertEquals(0, BigDecimal(1105000).compareTo(rows.last().principal))
        assertEquals(0, rows.last().balance.signum())
    }

    @Test
    fun `effective rate compounds the nominal rate monthly`() {
        assertEquals(0, BigDecimal("5.0534").compareTo(Amortization.effectiveOf(BigDecimal("4.94"))))
    }
}
