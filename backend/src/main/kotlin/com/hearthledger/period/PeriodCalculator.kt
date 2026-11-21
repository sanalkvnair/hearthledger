package com.hearthledger.period

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

data class Period(val start: LocalDate, val end: LocalDate)

fun interface BusinessCalendar {
    fun isBusinessDay(date: LocalDate): Boolean
}

val WEEKEND = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

/**
 * Budget periods run from the family's start day to the day before the next start; short months clamp to their last day.
 * With a [BusinessCalendar] a start that falls on a non-business day moves back to the previous business day.
 */
object PeriodCalculator {

    private fun startIn(month: YearMonth, day: Int, calendar: BusinessCalendar?): LocalDate {
        var start = month.atDay(minOf(day, month.lengthOfMonth()))
        if (calendar != null) {
            var guard = 0
            while (!calendar.isBusinessDay(start) && guard++ < 14) start = start.minusDays(1)
        }
        return start
    }

    fun containing(date: LocalDate, startDay: Int, calendar: BusinessCalendar? = null): Period {
        val month = YearMonth.from(date)
        // an adjusted start can move into the previous month, so look at a window of months around the date
        val starts = (-1L..2L).map { startIn(month.plusMonths(it), startDay, calendar) }
        val i = starts.indexOfLast { !it.isAfter(date) }
        return Period(starts[i], starts[i + 1].minusDays(1))
    }

    fun previous(period: Period, startDay: Int, calendar: BusinessCalendar? = null): Period =
        containing(period.start.minusDays(1), startDay, calendar)
}
