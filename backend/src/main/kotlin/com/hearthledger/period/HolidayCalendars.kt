package com.hearthledger.period

import de.focus_shift.jollyday.core.HolidayManager
import de.focus_shift.jollyday.core.ManagerParameters
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/** Weekends plus the public holidays of a country (ISO 3166 code, via Jollyday). */
@Component
class HolidayCalendars {
    private val managers = ConcurrentHashMap<String, HolidayManager>()

    private fun manager(country: String): HolidayManager =
        managers.computeIfAbsent(country.uppercase()) { HolidayManager.getInstance(ManagerParameters.create(it)) }

    fun isSupported(country: String): Boolean = runCatching { manager(country); true }.getOrDefault(false)

    fun forCountry(country: String): BusinessCalendar {
        val m = manager(country)
        return BusinessCalendar { d: LocalDate -> d.dayOfWeek !in WEEKEND && !m.isHoliday(d) }
    }
}
