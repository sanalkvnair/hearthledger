package com.openfamilybudget.savings

import com.openfamilybudget.account.AccountRepository
import com.openfamilybudget.account.AccountType
import com.openfamilybudget.common.badRequest
import com.openfamilybudget.common.money
import com.openfamilybudget.common.notFound
import com.openfamilybudget.currency.CurrencyService
import com.openfamilybudget.family.CurrentUser
import com.openfamilybudget.family.FamilyRepository
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.time.LocalDate

data class SavingsDetailRequest(
    val interestRate: BigDecimal? = null,
    val startDate: LocalDate? = null,
    val maturityDate: LocalDate? = null,
    val maturityAmount: BigDecimal? = null,
    /** Manually entered market / account value in the account's currency. */
    val currentValue: BigDecimal? = null,
    val valuedAt: LocalDate? = null,
)

data class SavingsAccountDto(
    val accountId: Long, val name: String, val type: AccountType, val currency: String, val institution: String?,
    val ownerUserId: Long?, val ownerName: String?,
    /** Opening balance plus net contributions, in the family's base currency. */
    val contributed: BigDecimal,
    /** Manual valuation converted to base currency, or contributions when none was entered. */
    val currentValue: BigDecimal,
    val gain: BigDecimal,
    val interestRate: BigDecimal?, val startDate: LocalDate?, val maturityDate: LocalDate?, val maturityAmount: BigDecimal?,
    val valuedAt: LocalDate?, val manualValue: BigDecimal?,
)

data class MemberSavings(val userId: Long?, val name: String?, val contributed: BigDecimal, val currentValue: BigDecimal)
data class SavingsOverview(val baseCurrency: String, val accounts: List<SavingsAccountDto>, val byMember: List<MemberSavings>, val totalContributed: BigDecimal, val totalValue: BigDecimal)

@RestController
@RequestMapping("/api/v1/savings")
class SavingsController(
    private val jdbc: JdbcClient,
    private val accounts: AccountRepository,
    private val families: FamilyRepository,
    private val currency: CurrencyService,
    private val current: CurrentUser,
) {
    private val savingsTypes = "('SAVINGS','FIXED_DEPOSIT','INVESTMENT')"

    @GetMapping
    fun overview(): SavingsOverview {
        val familyId = current.familyId()
        val base = families.findById(familyId).orElseThrow().baseCurrency
        val today = LocalDate.now()
        // contributions: transfers/income into the account minus transfers/expenses out of it
        val flows = jdbc.sql(
            """SELECT a.id,
                 COALESCE((SELECT sum(base_amount) FROM txn WHERE to_account_id = a.id), 0)
                 + COALESCE((SELECT sum(base_amount) FROM txn WHERE account_id = a.id AND type = 'INCOME'), 0)
                 - COALESCE((SELECT sum(base_amount) FROM txn WHERE account_id = a.id AND type IN ('TRANSFER', 'EXPENSE')), 0) AS net
               FROM account a WHERE a.family_id = :f AND a.type IN $savingsTypes""",
        ).param("f", familyId).query { rs, _ -> rs.getLong(1) to rs.getBigDecimal(2) }.list().toMap()

        val rows = jdbc.sql(
            """SELECT a.id, a.name, a.type, a.currency, a.institution, a.owner_user_id, u.name AS owner_name, a.opening_balance,
                      d.interest_rate, d.start_date, d.maturity_date, d.maturity_amount, d.current_value, d.valued_at
               FROM account a LEFT JOIN savings_detail d ON d.account_id = a.id LEFT JOIN app_user u ON u.id = a.owner_user_id
               WHERE a.family_id = :f AND a.type IN $savingsTypes AND NOT a.archived ORDER BY a.name""",
        ).param("f", familyId).query { rs, _ ->
            val id = rs.getLong("id")
            val cur = rs.getString("currency").trim()
            val rate = runCatching { currency.rateToBase(familyId, base, cur, today) }.getOrDefault(BigDecimal.ONE)
            val contributed = (rs.getBigDecimal("opening_balance") * rate + (flows[id] ?: BigDecimal.ZERO)).money()
            val manual = rs.getBigDecimal("current_value")
            val value = manual?.let { (it * rate).money() } ?: contributed
            SavingsAccountDto(
                id, rs.getString("name"), AccountType.valueOf(rs.getString("type")), cur, rs.getString("institution"),
                rs.getObject("owner_user_id") as Long?, rs.getString("owner_name"), contributed, value, (value - contributed).money(),
                rs.getBigDecimal("interest_rate"), rs.getDate("start_date")?.toLocalDate(), rs.getDate("maturity_date")?.toLocalDate(),
                rs.getBigDecimal("maturity_amount"), rs.getDate("valued_at")?.toLocalDate(), manual,
            )
        }.list()

        val byMember = rows.groupBy { it.ownerUserId }.map { (uid, list) ->
            MemberSavings(uid, list.first().ownerName, list.sumOf { it.contributed }, list.sumOf { it.currentValue })
        }.sortedBy { it.name ?: "" }
        return SavingsOverview(base, rows, byMember, rows.sumOf { it.contributed }, rows.sumOf { it.currentValue })
    }

    @PutMapping("/{accountId}/details")
    fun updateDetails(@PathVariable accountId: Long, @RequestBody r: SavingsDetailRequest) {
        val familyId = current.requireWriter().familyId
        val account = accounts.findByIdAndFamilyId(accountId, familyId) ?: throw notFound("Account")
        if (!account.type.isSavings) throw badRequest("NOT_SAVINGS", "Not a savings, fixed deposit or investment account")
        jdbc.sql(
            """INSERT INTO savings_detail (account_id, interest_rate, start_date, maturity_date, maturity_amount, current_value, valued_at)
               VALUES (:id, :rate, :start, :mdate, :mamount, :value, :valuedAt)
               ON CONFLICT (account_id) DO UPDATE SET interest_rate = EXCLUDED.interest_rate, start_date = EXCLUDED.start_date,
                 maturity_date = EXCLUDED.maturity_date, maturity_amount = EXCLUDED.maturity_amount,
                 current_value = EXCLUDED.current_value, valued_at = EXCLUDED.valued_at""",
        ).param("id", accountId).param("rate", r.interestRate).param("start", r.startDate, java.sql.Types.DATE)
            .param("mdate", r.maturityDate, java.sql.Types.DATE).param("mamount", r.maturityAmount).param("value", r.currentValue)
            .param("valuedAt", r.valuedAt ?: if (r.currentValue != null) LocalDate.now() else null, java.sql.Types.DATE).update()
    }
}
