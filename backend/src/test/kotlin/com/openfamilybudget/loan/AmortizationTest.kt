package com.openfamilybudget.loan

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
        val schedule = Amortization.schedule(l)
        assertEquals(24, schedule.size)
        assertEquals(0, schedule.last().balance.signum())
        assertTrue((Amortization.balanceAfter(l, 6) - schedule[5].balance).abs() < BigDecimal("0.05"))
        assertEquals(0, Amortization.balanceAfter(l, 24).signum())
    }
}
