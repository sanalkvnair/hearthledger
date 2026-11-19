package com.openfamilybudget.recurring

import com.openfamilybudget.common.Commitment
import com.openfamilybudget.common.Necessity
import com.openfamilybudget.txn.TxnType
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.CrudRepository
import java.math.BigDecimal
import java.time.LocalDate

enum class Frequency(val months: Int, val weeks: Int) {
    WEEKLY(0, 1), MONTHLY(1, 0), QUARTERLY(3, 0), YEARLY(12, 0);

    /** The [index]-th occurrence counted from [start]; computed from the anchor so month-end days never drift. */
    fun occurrence(start: LocalDate, index: Int): LocalDate =
        if (weeks > 0) start.plusWeeks(index.toLong() * weeks) else start.plusMonths(index.toLong() * months)

    val perMonth: BigDecimal
        get() = when (this) {
            WEEKLY -> BigDecimal("4.3333")
            else -> BigDecimal.ONE.divide(BigDecimal(months), 6, java.math.RoundingMode.HALF_UP)
        }
}

@Table("recurring_rule")
data class RecurringRule(
    @Id val id: Long? = null,
    val familyId: Long,
    val userId: Long,
    val type: TxnType,
    val amount: BigDecimal,
    val currency: String,
    val accountId: Long? = null,
    val categoryId: Long? = null,
    val commitment: Commitment? = null,
    val necessity: Necessity? = null,
    val note: String? = null,
    val frequency: Frequency,
    val startDate: LocalDate,
    val endDate: LocalDate? = null,
    val nextIndex: Int = 0,
    val loanId: Long? = null,
    val active: Boolean = true,
)

interface RecurringRuleRepository : CrudRepository<RecurringRule, Long> {
    fun findByFamilyId(familyId: Long): List<RecurringRule>
    fun findByIdAndFamilyId(id: Long, familyId: Long): RecurringRule?
    fun findByActiveTrue(): List<RecurringRule>
}
