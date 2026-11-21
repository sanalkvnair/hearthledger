package com.hearthledger.period

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

    private val weekdays = BusinessCalendar { it.dayOfWeek !in WEEKEND }

    @Test
    fun `start on a weekend moves back to the previous business day`() {
        // 25 Oct 2026 is a Sunday -> period starts Friday 23 Oct and ends before the next adjusted start (25 Nov is a Wednesday)
        val p = PeriodCalculator.containing(d("2026-10-30"), 25, weekdays)
        assertEquals(Period(d("2026-10-23"), d("2026-11-24")), p)
        // the days between the adjusted and nominal start belong to the new period
        assertEquals(p, PeriodCalculator.containing(d("2026-10-23"), 25, weekdays))
        assertEquals(Period(d("2026-09-25"), d("2026-10-22")), PeriodCalculator.containing(d("2026-10-22"), 25, weekdays))
        assertEquals(Period(d("2026-09-25"), d("2026-10-22")), PeriodCalculator.previous(p, 25, weekdays))
    }

    @Test
    fun `holidays are skipped too`() {
        val christmas = BusinessCalendar { it.dayOfWeek !in WEEKEND && it != d("2026-12-25") && it != d("2026-12-24") }
        // 25 Dec 2026 is a Friday and a holiday; 24th is also off -> start Wed 23 Dec
        assertEquals(d("2026-12-23"), PeriodCalculator.containing(d("2026-12-30"), 25, christmas).start)
    }

    @Test
    fun `without a calendar nothing changes`() {
        assertEquals(Period(d("2026-10-25"), d("2026-11-24")), PeriodCalculator.containing(d("2026-10-30"), 25))
    }
}
