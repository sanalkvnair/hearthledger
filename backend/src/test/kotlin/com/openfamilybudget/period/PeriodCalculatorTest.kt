package com.openfamilybudget.period

import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals

class PeriodCalculatorTest {
    private fun d(s: String) = LocalDate.parse(s)

    @Test
    fun `calendar month when start day is 1`() {
        assertEquals(Period(d("2026-03-01"), d("2026-03-31")), PeriodCalculator.containing(d("2026-03-15"), 1))
    }

    @Test
    fun `period spanning two months when start day is 25`() {
        assertEquals(Period(d("2026-01-25"), d("2026-02-24")), PeriodCalculator.containing(d("2026-02-10"), 25))
        assertEquals(Period(d("2026-02-25"), d("2026-03-24")), PeriodCalculator.containing(d("2026-02-25"), 25))
    }

    @Test
    fun `start day 31 clamps to last day of short months`() {
        assertEquals(Period(d("2026-01-31"), d("2026-02-27")), PeriodCalculator.containing(d("2026-02-10"), 31))
        assertEquals(Period(d("2026-02-28"), d("2026-03-30")), PeriodCalculator.containing(d("2026-03-05"), 31))
    }

    @Test
    fun `previous period is adjacent`() {
        val p = PeriodCalculator.containing(d("2026-03-10"), 25)
        assertEquals(Period(d("2026-02-25"), d("2026-03-24")), p)
        assertEquals(Period(d("2026-01-25"), d("2026-02-24")), PeriodCalculator.previous(p, 25))
    }
}
