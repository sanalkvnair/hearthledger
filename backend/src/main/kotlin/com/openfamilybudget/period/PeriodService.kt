package com.openfamilybudget.period

import com.openfamilybudget.common.money
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.LocalDate

data class Totals(val income: BigDecimal, val expense: BigDecimal, val savings: BigDecimal) {
    val net: BigDecimal get() = (income - expense - savings).money()
}

data class OpeningBalance(val computed: BigDecimal, val adjusted: BigDecimal?) {
    val effective: BigDecimal get() = adjusted ?: computed
}

@Service
class PeriodService(private val jdbc: JdbcClient) {

    private val savingsTypes = "('SAVINGS','FIXED_DEPOSIT','INVESTMENT')"

    /**
     * Savings = transfers into savings/FD/investment accounts minus withdrawals from them to
     * non-savings accounts. Both bounds are inclusive; null means unbounded.
     */
    fun totals(familyId: Long, from: LocalDate?, to: LocalDate?): Totals {
        val sql = """
            SELECT
              COALESCE(sum(t.base_amount) FILTER (WHERE t.type = 'INCOME'), 0) AS income,
              COALESCE(sum(t.base_amount) FILTER (WHERE t.type = 'EXPENSE'), 0) AS expense,
              COALESCE(sum(t.base_amount) FILTER (WHERE t.type = 'TRANSFER' AND ta.type IN $savingsTypes
                                                   AND COALESCE(a.type, '') NOT IN $savingsTypes), 0)
              - COALESCE(sum(t.base_amount) FILTER (WHERE t.type = 'TRANSFER' AND a.type IN $savingsTypes
                                                     AND COALESCE(ta.type, '') NOT IN $savingsTypes), 0) AS savings
            FROM txn t
            LEFT JOIN account a ON a.id = t.account_id
            LEFT JOIN account ta ON ta.id = t.to_account_id
            WHERE t.family_id = :f AND (CAST(:from AS date) IS NULL OR t.date >= :from) AND (CAST(:to AS date) IS NULL OR t.date <= :to)
        """
        return jdbc.sql(sql).param("f", familyId).param("from", from, java.sql.Types.DATE).param("to", to, java.sql.Types.DATE)
            .query { rs, _ -> Totals(rs.getBigDecimal("income").money(), rs.getBigDecimal("expense").money(), rs.getBigDecimal("savings").money()) }
            .single()
    }

    /**
     * Previous balance: the cumulative net of all earlier periods, anchored at the latest manual
     * adjustment (if any) on or before this period so that corrections carry forward.
     */
    fun openingBalance(familyId: Long, periodStart: LocalDate): OpeningBalance {
        val adjustments = jdbc.sql(
            "SELECT period_start, actual_opening_balance FROM period_adjustment WHERE family_id = :f AND period_start <= :s ORDER BY period_start DESC LIMIT 1",
        ).param("f", familyId).param("s", periodStart).query { rs, _ -> rs.getDate(1).toLocalDate() to rs.getBigDecimal(2) }.optional().orElse(null)

        val computed = totals(familyId, null, periodStart.minusDays(1)).net
        return when {
            adjustments == null -> OpeningBalance(computed, null)
            adjustments.first == periodStart -> OpeningBalance(computed, adjustments.second.money())
            else -> {
                val sinceAnchor = totals(familyId, adjustments.first, periodStart.minusDays(1)).net
                OpeningBalance(computed, (adjustments.second + sinceAnchor).money())
            }
        }
    }

    fun setOpeningBalance(familyId: Long, periodStart: LocalDate, amount: BigDecimal, note: String?) {
        jdbc.sql(
            """INSERT INTO period_adjustment (family_id, period_start, actual_opening_balance, note) VALUES (:f, :s, :a, :n)
               ON CONFLICT (family_id, period_start) DO UPDATE SET actual_opening_balance = EXCLUDED.actual_opening_balance, note = EXCLUDED.note""",
        ).param("f", familyId).param("s", periodStart).param("a", amount).param("n", note).update()
    }

    fun clearOpeningBalance(familyId: Long, periodStart: LocalDate) {
        jdbc.sql("DELETE FROM period_adjustment WHERE family_id = :f AND period_start = :s").param("f", familyId).param("s", periodStart).update()
    }
}
