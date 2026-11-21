package com.hearthledger.savings

import com.hearthledger.account.Account
import com.hearthledger.account.AccountOwners
import com.hearthledger.account.AccountRepository
import com.hearthledger.account.AccountType
import com.hearthledger.account.Ownership
import com.hearthledger.common.badRequest
import com.hearthledger.common.money
import com.hearthledger.common.notFound
import com.hearthledger.currency.CurrencyService
import com.hearthledger.family.CurrentUser
import com.hearthledger.family.FamilyRepository
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth

data class SavingsDetailRequest(
    val interestRate: BigDecimal? = null,
    val startDate: LocalDate? = null,
    val maturityDate: LocalDate? = null,
    val maturityAmount: BigDecimal? = null,
    /** Shortcut that records a valuation (see /valuations) in the account's currency. */
    val currentValue: BigDecimal? = null,
    val valuedAt: LocalDate? = null,
)

data class ValuationRequest(
    val valuedOn: LocalDate? = null,
    /** Value of the account on [valuedOn] in its own currency. */
    val marketValue: BigDecimal? = null,
    /** Alternative input: profit (+) or loss (−) versus the invested amount on that date. */
    val profitLoss: BigDecimal? = null,
    val note: String? = null,
)

data class ValuationDto(
    val id: Long, val valuedOn: LocalDate, val marketValue: BigDecimal, val invested: BigDecimal, val profitLoss: BigDecimal,
    val profitLossPercent: BigDecimal?, val note: String?,
)

data class SavingsAccountDto(
    val accountId: Long, val name: String, val type: AccountType, val currency: String, val institution: String?,
    val ownership: Ownership, val ownerIds: List<Long>, val ownerNames: List<String>,
    /** Opening balance plus net contributions, in the family's base currency. */
    val contributed: BigDecimal,
    /** Latest valuation converted to base currency, or contributions when none was entered. */
    val currentValue: BigDecimal,
    val gain: BigDecimal,
    val interestRate: BigDecimal?, val startDate: LocalDate?, val maturityDate: LocalDate?, val maturityAmount: BigDecimal?,
    val valuedAt: LocalDate?, val manualValue: BigDecimal?,
)

/** Joint accounts count in full under each of their owners; FAMILY accounts appear under userId = null. */
data class MemberSavings(val userId: Long?, val name: String?, val contributed: BigDecimal, val currentValue: BigDecimal)

data class SavingsOverview(
    val baseCurrency: String, val accounts: List<SavingsAccountDto>, val byMember: List<MemberSavings>,
    val totalContributed: BigDecimal, val totalValue: BigDecimal,
    /** JOINT + FAMILY accounts. */
    val sharedContributed: BigDecimal, val sharedValue: BigDecimal,
    /** PRIVATE accounts. */
    val individualContributed: BigDecimal, val individualValue: BigDecimal,
)

data class HistoryPoint(val date: LocalDate, val invested: BigDecimal, val value: BigDecimal, val profitLoss: BigDecimal)
data class SavingsHistory(val baseCurrency: String, val points: List<HistoryPoint>)

@RestController
@RequestMapping("/api/v1/savings")
class SavingsController(
    private val jdbc: JdbcClient,
    private val accounts: AccountRepository,
    private val owners: AccountOwners,
    private val families: FamilyRepository,
    private val currency: CurrencyService,
    private val current: CurrentUser,
) {
    private val savingsTypes = "('SAVINGS','FIXED_DEPOSIT','INVESTMENT')"

    private data class Flow(val date: LocalDate, val accountId: Long?, val toAccountId: Long?, val type: String, val base: BigDecimal)

    /** Contribution flows touching savings-type accounts: transfers/income in, transfers/expenses out. */
    private fun flows(familyId: Long): List<Flow> =
        jdbc.sql(
            """SELECT t.date, t.account_id, t.to_account_id, t.type, t.base_amount FROM txn t
               WHERE t.family_id = :f AND (t.account_id IN (SELECT id FROM account WHERE family_id = :f AND type IN $savingsTypes)
                                           OR t.to_account_id IN (SELECT id FROM account WHERE family_id = :f AND type IN $savingsTypes))""",
        ).param("f", familyId).query { rs, _ ->
            Flow(rs.getDate(1).toLocalDate(), rs.getObject(2) as Long?, rs.getObject(3) as Long?, rs.getString(4), rs.getBigDecimal(5))
        }.list()

    private fun net(flows: List<Flow>, accountId: Long, until: LocalDate?): BigDecimal {
        var sum = BigDecimal.ZERO
        for (f in flows) {
            if (until != null && f.date.isAfter(until)) continue
            if (f.toAccountId == accountId) sum += f.base
            if (f.accountId == accountId) {
                if (f.type == "INCOME") sum += f.base else if (f.type == "TRANSFER" || f.type == "EXPENSE") sum -= f.base
            }
        }
        return sum
    }

    private fun valuations(accountId: Long): List<Triple<Long, LocalDate, BigDecimal>> =
        jdbc.sql("SELECT id, valued_on, market_value FROM account_valuation WHERE account_id = :a ORDER BY valued_on")
            .param("a", accountId).query { rs, _ -> Triple(rs.getLong(1), rs.getDate(2).toLocalDate(), rs.getBigDecimal(3)) }.list()

    @GetMapping
    fun overview(): SavingsOverview {
        val familyId = current.familyId()
        val base = families.findById(familyId).orElseThrow().baseCurrency
        val today = LocalDate.now()
        val flows = flows(familyId)
        val ownerIds = owners.byAccount(familyId)
        val names = jdbc.sql("SELECT u.id, u.name FROM family_member m JOIN app_user u ON u.id = m.user_id WHERE m.family_id = :f")
            .param("f", familyId).query { rs, _ -> rs.getLong(1) to rs.getString(2) }.list().toMap()

        val rows = jdbc.sql(
            """SELECT a.id, a.name, a.type, a.currency, a.institution, a.ownership, a.opening_balance,
                      d.interest_rate, d.start_date, d.maturity_date, d.maturity_amount,
                      v.market_value, v.valued_on
               FROM account a LEFT JOIN savings_detail d ON d.account_id = a.id
               LEFT JOIN LATERAL (SELECT market_value, valued_on FROM account_valuation WHERE account_id = a.id ORDER BY valued_on DESC LIMIT 1) v ON TRUE
               WHERE a.family_id = :f AND a.type IN $savingsTypes AND NOT a.archived ORDER BY a.name""",
        ).param("f", familyId).query { rs, _ ->
            val id = rs.getLong("id")
            val cur = rs.getString("currency").trim()
            val rate = currency.rateToBase(familyId, base, cur, today)
            val contributed = (rs.getBigDecimal("opening_balance") * rate + net(flows, id, null)).money()
            val manual = rs.getBigDecimal("market_value")
            val value = manual?.let { (it * rate).money() } ?: contributed
            val oids = ownerIds[id] ?: emptyList()
            SavingsAccountDto(
                id, rs.getString("name"), AccountType.valueOf(rs.getString("type")), cur, rs.getString("institution"),
                Ownership.valueOf(rs.getString("ownership")), oids, oids.mapNotNull(names::get), contributed, value, (value - contributed).money(),
                rs.getBigDecimal("interest_rate"), rs.getDate("start_date")?.toLocalDate(), rs.getDate("maturity_date")?.toLocalDate(),
                rs.getBigDecimal("maturity_amount"), rs.getDate("valued_on")?.toLocalDate(), manual,
            )
        }.list()

        val byMember = buildList {
            names.forEach { (uid, name) ->
                val mine = rows.filter { uid in it.ownerIds }
                if (mine.isNotEmpty()) add(MemberSavings(uid, name, mine.sumOf { it.contributed }, mine.sumOf { it.currentValue }))
            }
            val family = rows.filter { it.ownership == Ownership.FAMILY }
            if (family.isNotEmpty()) add(MemberSavings(null, null, family.sumOf { it.contributed }, family.sumOf { it.currentValue }))
        }.sortedBy { it.name ?: "" }
        val shared = rows.filter { it.ownership != Ownership.PRIVATE }
        val individual = rows.filter { it.ownership == Ownership.PRIVATE }
        return SavingsOverview(
            base, rows, byMember, rows.sumOf { it.contributed }, rows.sumOf { it.currentValue },
            shared.sumOf { it.contributed }, shared.sumOf { it.currentValue }, individual.sumOf { it.contributed }, individual.sumOf { it.currentValue },
        )
    }

    @PutMapping("/{accountId}/details")
    @Transactional
    fun updateDetails(@PathVariable accountId: Long, @RequestBody r: SavingsDetailRequest) {
        val familyId = current.requireWriter().familyId
        savingsAccount(accountId, familyId)
        jdbc.sql(
            """INSERT INTO savings_detail (account_id, interest_rate, start_date, maturity_date, maturity_amount)
               VALUES (:id, :rate, :start, :mdate, :mamount)
               ON CONFLICT (account_id) DO UPDATE SET interest_rate = EXCLUDED.interest_rate, start_date = EXCLUDED.start_date,
                 maturity_date = EXCLUDED.maturity_date, maturity_amount = EXCLUDED.maturity_amount""",
        ).param("id", accountId).param("rate", r.interestRate).param("start", r.startDate, java.sql.Types.DATE)
            .param("mdate", r.maturityDate, java.sql.Types.DATE).param("mamount", r.maturityAmount).update()
        if (r.currentValue != null) upsertValuation(accountId, r.valuedAt ?: LocalDate.now(), r.currentValue, null)
    }

    private fun savingsAccount(accountId: Long, familyId: Long): Account {
        val account = accounts.findByIdAndFamilyId(accountId, familyId) ?: throw notFound("Account")
        if (!account.type.isSavings) throw badRequest("NOT_SAVINGS", "Not a savings, fixed deposit or investment account")
        return account
    }

    private fun upsertValuation(accountId: Long, on: LocalDate, value: BigDecimal, note: String?) {
        jdbc.sql(
            """INSERT INTO account_valuation (account_id, valued_on, market_value, note) VALUES (:a, :d, :v, :n)
               ON CONFLICT (account_id, valued_on) DO UPDATE SET market_value = EXCLUDED.market_value, note = EXCLUDED.note""",
        ).param("a", accountId).param("d", on).param("v", value.money()).param("n", note).update()
    }

    /** Invested amount on [date] expressed in the account's own currency (converted at today's rate). */
    private fun investedInAccountCurrency(account: Account, flows: List<Flow>, date: LocalDate, baseCurrency: String): BigDecimal {
        val rate = currency.rateToBase(account.familyId, baseCurrency, account.currency.trim(), LocalDate.now())
        return account.openingBalance + net(flows, account.id!!, date).divide(rate, 4, RoundingMode.HALF_UP)
    }

    @GetMapping("/{accountId}/valuations")
    fun listValuations(@PathVariable accountId: Long): List<ValuationDto> {
        val familyId = current.familyId()
        val account = savingsAccount(accountId, familyId)
        val base = families.findById(familyId).orElseThrow().baseCurrency
        val flows = flows(familyId)
        return valuations(accountId).map { (id, date, value) ->
            val invested = investedInAccountCurrency(account, flows, date, base).money()
            val pl = (value - invested).money()
            val note = jdbc.sql("SELECT note FROM account_valuation WHERE id = :i").param("i", id).query(String::class.java).optional().orElse(null)
            ValuationDto(
                id, date, value.money(), invested, pl,
                if (invested.signum() > 0) pl.multiply(BigDecimal(100)).divide(invested, 2, RoundingMode.HALF_UP) else null, note,
            )
        }.sortedByDescending { it.valuedOn }
    }

    @PostMapping("/{accountId}/valuations")
    @Transactional
    fun addValuation(@PathVariable accountId: Long, @RequestBody r: ValuationRequest): ValuationDto {
        val familyId = current.requireWriter().familyId
        val account = savingsAccount(accountId, familyId)
        val on = r.valuedOn ?: LocalDate.now()
        val base = families.findById(familyId).orElseThrow().baseCurrency
        val value = r.marketValue ?: r.profitLoss?.let { investedInAccountCurrency(account, flows(familyId), on, base) + it }
            ?: throw badRequest("VALUATION", "Provide a market value or a profit/loss amount")
        if (value.signum() < 0) throw badRequest("VALUATION", "Value cannot be negative")
        upsertValuation(accountId, on, value, r.note)
        return listValuations(accountId).first { it.valuedOn == on }
    }

    @DeleteMapping("/{accountId}/valuations/{id}")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    fun deleteValuation(@PathVariable accountId: Long, @PathVariable id: Long) {
        savingsAccount(accountId, current.requireWriter().familyId)
        jdbc.sql("DELETE FROM account_valuation WHERE id = :i AND account_id = :a").param("i", id).param("a", accountId).update()
    }

    /**
     * Invested amount versus value over time (month ends plus today). Filter by a single account, or by [scope]
     * (ALL, SHARED = joint + family, INDIVIDUAL = private) optionally narrowed to one member.
     */
    @GetMapping("/history")
    fun history(
        @RequestParam(required = false) accountId: Long?,
        @RequestParam(defaultValue = "ALL") scope: String,
        @RequestParam(required = false) userId: Long?,
    ): SavingsHistory {
        val familyId = current.familyId()
        val base = families.findById(familyId).orElseThrow().baseCurrency
        val ownerIds = owners.byAccount(familyId)
        val selected = accounts.findByFamilyId(familyId).filter { it.type.isSavings && !it.archived }.filter { a ->
            when {
                accountId != null -> a.id == accountId
                else -> when (scope.uppercase()) {
                    "SHARED" -> a.ownership != Ownership.PRIVATE
                    "INDIVIDUAL" -> a.ownership == Ownership.PRIVATE
                    else -> true
                } && (userId == null || userId in (ownerIds[a.id] ?: emptyList()))
            }
        }
        if (selected.isEmpty()) return SavingsHistory(base, emptyList())

        val flows = flows(familyId)
        val today = LocalDate.now()
        val vals = selected.associate { it.id!! to valuations(it.id) }
        val first = (flows.filter { f -> selected.any { it.id == f.accountId || it.id == f.toAccountId } }.map { it.date } +
            vals.values.flatten().map { it.second }).minOrNull() ?: return SavingsHistory(base, emptyList())
        val rates = selected.associate { it.id!! to currency.rateToBase(familyId, base, it.currency.trim(), today) }

        val dates = generateSequence(YearMonth.from(first)) { it.plusMonths(1) }.takeWhile { !it.isAfter(YearMonth.from(today)) }
            .map { it.atEndOfMonth() }.filter { it.isBefore(today) }.toList() + today
        val points = dates.map { d ->
            var invested = BigDecimal.ZERO
            var value = BigDecimal.ZERO
            for (a in selected) {
                val inv = a.openingBalance * rates.getValue(a.id!!) + net(flows, a.id, d)
                invested += inv
                val latest = vals.getValue(a.id).lastOrNull { !it.second.isAfter(d) }
                value += latest?.let { it.third * rates.getValue(a.id) } ?: inv
            }
            HistoryPoint(d, invested.money(), value.money(), (value - invested).money())
        }
        return SavingsHistory(base, points)
    }
}
