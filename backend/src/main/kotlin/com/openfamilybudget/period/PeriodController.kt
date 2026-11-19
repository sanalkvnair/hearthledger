package com.openfamilybudget.period

import com.openfamilybudget.family.CurrentUser
import com.openfamilybudget.family.FamilyRepository
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.time.LocalDate

data class OpeningBalanceRequest(val amount: BigDecimal, val note: String? = null)

@RestController
@RequestMapping("/api/v1/periods")
class PeriodController(private val service: PeriodService, private val current: CurrentUser, private val families: FamilyRepository) {

    @GetMapping("/current")
    fun current(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate?): Period =
        PeriodCalculator.containing(date ?: LocalDate.now(), families.findById(current.familyId()).orElseThrow().cycleStartDay)

    /** Overrides the carried-over balance of a period with the user's actual amount. */
    @PutMapping("/{start}/opening-balance")
    fun setOpening(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) start: LocalDate, @RequestBody r: OpeningBalanceRequest) =
        service.setOpeningBalance(current.requireWriter().familyId, start, r.amount, r.note)

    @DeleteMapping("/{start}/opening-balance")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun clearOpening(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) start: LocalDate) =
        service.clearOpeningBalance(current.requireWriter().familyId, start)
}
