package com.openfamilybudget.period

import java.time.LocalDate
import java.time.YearMonth

data class Period(val start: LocalDate, val end: LocalDate)

/** Budget periods run from the family's start day to the day before the next start; short months clamp to their last day. */
object PeriodCalculator {

    private fun startIn(month: YearMonth, day: Int): LocalDate = month.atDay(minOf(day, month.lengthOfMonth()))

    fun containing(date: LocalDate, startDay: Int): Period {
        val month = YearMonth.from(date)
        var start = startIn(month, startDay)
        if (date.isBefore(start)) start = startIn(month.minusMonths(1), startDay)
        val next = startIn(YearMonth.from(start).plusMonths(1), startDay)
        return Period(start, next.minusDays(1))
    }

    fun previous(period: Period, startDay: Int): Period = containing(period.start.minusDays(1), startDay)
}
