package com.openfamilybudget.currency

import com.openfamilybudget.common.badRequest
import com.openfamilybudget.family.CurrentUser
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Pattern
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.time.LocalDate

data class ExchangeRateDto(val id: Long, val fromCurrency: String, val toCurrency: String, val rate: BigDecimal, val effectiveDate: LocalDate)

data class ExchangeRateRequest(
    @field:Pattern(regexp = "[A-Za-z]{3}") val fromCurrency: String,
    @field:DecimalMin("0.00000001") val rate: BigDecimal,
    val effectiveDate: LocalDate,
)

@Service
class CurrencyService(private val jdbc: JdbcClient) {

    /** Rate to convert 1 unit of [from] into the family's base currency, effective on or before [date]. */
    fun rateToBase(familyId: Long, baseCurrency: String, from: String, date: LocalDate): BigDecimal {
        if (from.equals(baseCurrency, ignoreCase = true)) return BigDecimal.ONE
        return jdbc.sql(
            """SELECT rate FROM exchange_rate WHERE family_id = :f AND from_currency = :from AND to_currency = :to
               AND effective_date <= :d ORDER BY effective_date DESC LIMIT 1""",
        ).param("f", familyId).param("from", from.uppercase()).param("to", baseCurrency).param("d", date)
            .query(BigDecimal::class.java).optional()
            .orElseThrow { badRequest("NO_EXCHANGE_RATE", "No $from→$baseCurrency exchange rate defined on or before $date") }
    }
}

/** Exchange rates are entered manually per family; the target is always the family's base currency. */
@RestController
@RequestMapping("/api/v1/exchange-rates")
class ExchangeRateController(
    private val jdbc: JdbcClient,
    private val current: CurrentUser,
    private val families: com.openfamilybudget.family.FamilyRepository,
) {
    @GetMapping
    fun list(): List<ExchangeRateDto> =
        jdbc.sql("SELECT id, from_currency, to_currency, rate, effective_date FROM exchange_rate WHERE family_id = :f ORDER BY effective_date DESC, from_currency")
            .param("f", current.familyId())
            .query { rs, _ ->
                ExchangeRateDto(rs.getLong("id"), rs.getString("from_currency"), rs.getString("to_currency"), rs.getBigDecimal("rate"), rs.getDate("effective_date").toLocalDate())
            }.list()

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun upsert(@Valid @RequestBody r: ExchangeRateRequest) {
        val familyId = current.requireWriter().familyId
        val base = families.findById(familyId).orElseThrow().baseCurrency
        if (r.fromCurrency.equals(base, ignoreCase = true)) throw badRequest("INVALID_CURRENCY", "Rate source cannot be the base currency")
        jdbc.sql(
            """INSERT INTO exchange_rate (family_id, from_currency, to_currency, rate, effective_date)
               VALUES (:f, :from, :to, :rate, :d)
               ON CONFLICT (family_id, from_currency, to_currency, effective_date) DO UPDATE SET rate = EXCLUDED.rate""",
        ).param("f", familyId).param("from", r.fromCurrency.uppercase()).param("to", base).param("rate", r.rate).param("d", r.effectiveDate).update()
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: Long) {
        jdbc.sql("DELETE FROM exchange_rate WHERE id = :id AND family_id = :f").param("id", id).param("f", current.requireWriter().familyId).update()
    }
}
