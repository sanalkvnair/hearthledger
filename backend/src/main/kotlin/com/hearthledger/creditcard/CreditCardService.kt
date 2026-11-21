package com.hearthledger.creditcard

import com.hearthledger.account.Account
import com.hearthledger.account.AccountOwners
import com.hearthledger.account.AccountRepository
import com.hearthledger.account.AccountType
import com.hearthledger.common.badRequest
import com.hearthledger.common.money
import com.hearthledger.common.notFound
import com.hearthledger.family.CurrentUser
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

data class CardSummary(
    val accountId: Long, val name: String, val currency: String, val ownerIds: List<Long>,
    val statementDay: Int, val dueDay: Int,
    /** Purchases since the last statement closed (not yet billed). */
    val openFrom: LocalDate, val openAmount: BigDecimal,
    /** The most recently closed statement. */
    val statementFrom: LocalDate, val statementClosedOn: LocalDate, val statementAmount: BigDecimal,
    val dueDate: LocalDate, val paid: BigDecimal, val remaining: BigDecimal, val overdue: Boolean, val daysToDue: Long,
)

/**
 * Card purchases are expenses on the purchase date; the bill payment is a transfer bank -> card. A statement covers the
 * days after the previous statement day up to and including the statement day. Statement and due days are never moved
 * for weekends or holidays. Amounts are in the family's base currency.
 */
@Service
class CreditCardService(private val jdbc: JdbcClient, private val accounts: AccountRepository, private val owners: AccountOwners) {

    private fun dayIn(month: YearMonth, day: Int): LocalDate = month.atDay(minOf(day, month.lengthOfMonth()))

    /** Latest statement closing date on or before [date]. */
    fun closingOnOrBefore(date: LocalDate, statementDay: Int): LocalDate {
        val d = dayIn(YearMonth.from(date), statementDay)
        return if (d.isAfter(date)) dayIn(YearMonth.from(date).minusMonths(1), statementDay) else d
    }

    fun dueAfter(closing: LocalDate, dueDay: Int): LocalDate {
        val d = dayIn(YearMonth.from(closing), dueDay)
        return if (d.isAfter(closing)) d else dayIn(YearMonth.from(closing).plusMonths(1), dueDay)
    }

    private fun spend(accountId: Long, from: LocalDate, to: LocalDate): BigDecimal =
        jdbc.sql(
            """SELECT COALESCE(sum(CASE WHEN type = 'EXPENSE' THEN base_amount WHEN type = 'INCOME' THEN -base_amount ELSE 0 END), 0)
               FROM txn WHERE account_id = :a AND date BETWEEN :from AND :to""",
        ).param("a", accountId).param("from", from).param("to", to).query(BigDecimal::class.java).single()

    private fun payments(accountId: Long, from: LocalDate, to: LocalDate): BigDecimal =
        jdbc.sql("SELECT COALESCE(sum(base_amount), 0) FROM txn WHERE to_account_id = :a AND type = 'TRANSFER' AND date BETWEEN :from AND :to")
            .param("a", accountId).param("from", from).param("to", to).query(BigDecimal::class.java).single()

    fun summary(account: Account, ownerIds: List<Long>, today: LocalDate = LocalDate.now()): CardSummary {
        val sd = account.statementDay ?: throw badRequest("CARD_CYCLE", "Set the statement day and due day on this card first")
        val dd = account.dueDay ?: throw badRequest("CARD_CYCLE", "Set the statement day and due day on this card first")
        val id = account.id!!
        val closed = closingOnOrBefore(today, sd)
        val previousClosed = closingOnOrBefore(closed.minusDays(1), sd)
        val statementAmount = spend(id, previousClosed.plusDays(1), closed).money()
        val openAmount = spend(id, closed.plusDays(1), today).money()
        val due = dueAfter(closed, dd)
        val paid = payments(id, closed.plusDays(1), today).money()
        val remaining = (statementAmount - paid).max(BigDecimal.ZERO).money()
        return CardSummary(
            id, account.name, account.currency.trim(), ownerIds, sd, dd, closed.plusDays(1), openAmount,
            previousClosed.plusDays(1), closed, statementAmount, due, paid, remaining,
            remaining.signum() > 0 && today.isAfter(due), ChronoUnit.DAYS.between(today, due),
        )
    }

    fun summaries(familyId: Long, today: LocalDate = LocalDate.now()): List<CardSummary> {
        val ownerIds = owners.byAccount(familyId)
        return accounts.findByFamilyId(familyId)
            .filter { it.type == AccountType.CREDIT_CARD && !it.archived && it.statementDay != null && it.dueDay != null }
            .sortedBy { it.name }.map { summary(it, ownerIds[it.id] ?: emptyList(), today) }
    }

    /** Unpaid closed statements, optionally only for cards owned by [userId]. */
    fun unpaidTotal(familyId: Long, userId: Long?): BigDecimal =
        summaries(familyId).filter { userId == null || userId in it.ownerIds }.sumOf { it.remaining }.money()
}

@RestController
@RequestMapping("/api/v1")
class CreditCardController(
    private val service: CreditCardService,
    private val accounts: AccountRepository,
    private val owners: AccountOwners,
    private val current: CurrentUser,
) {
    @GetMapping("/accounts/{id}/card-summary")
    fun card(@PathVariable id: Long): CardSummary {
        val familyId = current.familyId()
        val a = accounts.findByIdAndFamilyId(id, familyId)?.takeIf { it.type == AccountType.CREDIT_CARD } ?: throw notFound("Credit card")
        return service.summary(a, owners.byAccount(familyId)[id] ?: emptyList())
    }

    @GetMapping("/dashboard/card-bills")
    fun bills(): List<CardSummary> = service.summaries(current.familyId())
}
