package com.hearthledger.importing

import com.hearthledger.account.AccountRepository
import com.hearthledger.common.Commitment
import com.hearthledger.common.Necessity
import com.hearthledger.common.badRequest
import com.hearthledger.common.notFound
import com.hearthledger.family.CurrentUser
import com.hearthledger.txn.TxnRequest
import com.hearthledger.txn.TxnService
import com.hearthledger.txn.TxnType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.LocalDate

data class ImportRow(
    val date: LocalDate,
    val description: String,
    val amount: BigDecimal,
    val type: TxnType,
    val categoryId: Long? = null,
    val necessity: Necessity? = null,
    val commitment: Commitment? = null,
    val duplicate: Boolean = false,
    val error: String? = null,
)

data class ImportPreview(val rows: List<ImportRow>, val errors: List<String>)

data class RawRow(val date: LocalDate, val description: String, val amount: BigDecimal, val type: TxnType)

@Service
class ImportService(
    private val jdbc: JdbcClient,
    private val accounts: AccountRepository,
    private val txns: TxnService,
    private val current: CurrentUser,
) {
    fun mapCsv(csv: ParsedCsv, m: CsvMapping): Pair<List<RawRow>, List<String>> {
        fun idx(name: String?) = name?.let { n -> csv.headers.indexOf(n).also { if (it < 0) throw badRequest("BAD_MAPPING", "Column '$n' not found") } }
        val dateI = idx(m.dateColumn)!!
        val descI = idx(m.descriptionColumn)!!
        val amtI = idx(m.amountColumn)
        val debitI = idx(m.debitColumn)
        val creditI = idx(m.creditColumn)
        if (amtI == null && debitI == null && creditI == null) throw badRequest("BAD_MAPPING", "Map an amount column or debit/credit columns")
        val errors = mutableListOf<String>()
        val rows = csv.rows.mapIndexedNotNull { n, r ->
            try {
                val date = CsvParser.parseDate(r[dateI], m.dateFormat)
                val signed: BigDecimal = if (amtI != null) {
                    CsvParser.parseAmount(r.getOrElse(amtI) { "" }, m.decimalSeparator) ?: error("no amount")
                } else {
                    val debit = debitI?.let { CsvParser.parseAmount(r.getOrElse(it) { "" }, m.decimalSeparator) }?.abs()
                    val credit = creditI?.let { CsvParser.parseAmount(r.getOrElse(it) { "" }, m.decimalSeparator) }?.abs()
                    when {
                        debit != null && debit.signum() != 0 -> debit.negate()
                        credit != null -> credit
                        else -> error("no amount")
                    }
                }
                val v = if (m.invertSign) signed.negate() else signed
                if (v.signum() == 0) return@mapIndexedNotNull null
                RawRow(date, r.getOrElse(descI) { "" }, v.abs(), if (v.signum() < 0) TxnType.EXPENSE else TxnType.INCOME)
            } catch (e: Exception) {
                errors += "Row ${n + 2}: ${e.message}"
                null
            }
        }
        return rows to errors
    }

    fun hash(r: RawRow): String {
        val s = "${r.date}|${r.amount.stripTrailingZeros().toPlainString()}|${r.type}|${r.description.lowercase().replace(Regex("\\s+"), " ").trim()}"
        return MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /** Applies keyword rules (highest priority first) and flags duplicates against existing entries. */
    fun enrich(accountId: Long, raw: List<RawRow>, errors: List<String>): ImportPreview {
        val familyId = current.familyId()
        accounts.findByIdAndFamilyId(accountId, familyId) ?: throw notFound("Account")
        val rules = jdbc.sql(
            """SELECT r.keyword, r.category_id, c.type, c.default_necessity FROM categorization_rule r
               JOIN category c ON c.id = r.category_id WHERE r.family_id = :f ORDER BY r.priority DESC, r.id""",
        ).param("f", familyId).query { rs, _ ->
            listOf(rs.getString(1).lowercase(), rs.getLong(2), rs.getString(3), rs.getString(4))
        }.list()
        val knownHashes = raw.map { hash(it) }.let { hs ->
            if (hs.isEmpty()) emptySet() else jdbc.sql("SELECT import_hash FROM txn WHERE family_id = :f AND import_hash IN (:h)")
                .param("f", familyId).param("h", hs).query(String::class.java).set()
        }
        val rows = raw.map { r ->
            val rule = rules.firstOrNull { r.description.lowercase().contains(it[0] as String) && it[2] == r.type.name }
            val manual = jdbc.sql(
                "SELECT count(*) FROM txn WHERE family_id = :f AND account_id = :a AND date = :d AND amount = :m AND type = :t",
            ).param("f", familyId).param("a", accountId).param("d", r.date).param("m", r.amount).param("t", r.type.name)
                .query(Long::class.java).single() > 0
            ImportRow(
                r.date, r.description, r.amount, r.type,
                categoryId = rule?.get(1) as Long?,
                necessity = (rule?.get(3) as String?)?.let { Necessity.valueOf(it) }.takeIf { r.type == TxnType.EXPENSE },
                duplicate = hash(r) in knownHashes || manual,
            )
        }
        return ImportPreview(rows, errors)
    }

    @Transactional
    fun commit(accountId: Long, rows: List<ImportRow>): Int {
        val m = current.requireWriter()
        val account = accounts.findByIdAndFamilyId(accountId, m.familyId) ?: throw notFound("Account")
        rows.forEach { r ->
            val raw = RawRow(r.date, r.description, r.amount, if (r.type == TxnType.REFUND) TxnType.INCOME else r.type)
            txns.create(
                TxnRequest(
                    type = r.type, amount = r.amount, currency = account.currency, date = r.date, accountId = accountId,
                    categoryId = r.categoryId,
                    commitment = if (r.type == TxnType.EXPENSE) r.commitment else null,
                    necessity = if (r.type == TxnType.EXPENSE || r.type == TxnType.REFUND) r.necessity else null,
                    note = r.description.ifBlank { null }, importHash = hash(raw),
                ),
            )
        }
        return rows.size
    }
}
