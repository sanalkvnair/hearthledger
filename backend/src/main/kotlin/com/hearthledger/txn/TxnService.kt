package com.hearthledger.txn

import com.hearthledger.account.AccountRepository
import com.hearthledger.category.CategoryRepository
import com.hearthledger.category.CategoryType
import com.hearthledger.common.Commitment
import com.hearthledger.common.badRequest
import com.hearthledger.common.money
import com.hearthledger.common.notFound
import com.hearthledger.currency.CurrencyService
import com.hearthledger.family.CurrentUser
import com.hearthledger.family.FamilyRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class TxnService(
    private val repo: TxnRepository,
    private val accounts: AccountRepository,
    private val categories: CategoryRepository,
    private val families: FamilyRepository,
    private val loans: com.hearthledger.loan.LoanRepository,
    private val currency: CurrencyService,
    private val jdbc: org.springframework.jdbc.core.simple.JdbcClient,
    private val current: CurrentUser,
) {
    @Transactional
    fun create(r: TxnRequest): Txn {
        val m = current.requireWriter()
        return repo.save(build(null, m.familyId, m.userId, r))
    }

    /** Creates all rows or none: if any row is invalid nothing is saved and every row error is reported. */
    @Transactional
    fun createBatch(items: List<TxnRequest>): List<Txn> {
        val m = current.requireWriter()
        val errors = mutableListOf<Map<String, Any>>()
        val built = items.mapIndexedNotNull { i, r ->
            try {
                build(null, m.familyId, m.userId, r)
            } catch (e: com.hearthledger.common.ApiException) {
                errors += mapOf("row" to i, "code" to e.code, "message" to (e.message ?: ""))
                null
            }
        }
        if (errors.isNotEmpty()) {
            throw com.hearthledger.common.ApiException(
                org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "BATCH_INVALID",
                "${errors.size} of ${items.size} rows are invalid", errors,
            )
        }
        return repo.saveAll(built).toList()
    }

    @Transactional
    fun update(id: Long, r: TxnRequest): Txn {
        val m = current.requireWriter()
        val existing = repo.findByIdAndFamilyId(id, m.familyId) ?: throw notFound("Transaction")
        return repo.save(build(existing.id, m.familyId, existing.userId, r).copy(createdAt = existing.createdAt, recurringRuleId = existing.recurringRuleId, importHash = existing.importHash, loanInterestPart = existing.loanInterestPart, loanPrincipalPart = existing.loanPrincipalPart, loanScheduleNo = existing.loanScheduleNo))
    }

    @Transactional
    fun delete(id: Long) {
        val m = current.requireWriter()
        repo.delete(repo.findByIdAndFamilyId(id, m.familyId) ?: throw notFound("Transaction"))
    }

    private fun build(id: Long?, familyId: Long, userId: Long, r: TxnRequest): Txn {
        val family = families.findById(familyId).orElseThrow()
        if (r.accountId != null) accounts.findByIdAndFamilyId(r.accountId, familyId) ?: throw notFound("Account")
        if (r.toAccountId != null) accounts.findByIdAndFamilyId(r.toAccountId, familyId) ?: throw notFound("Destination account")

        if (r.type == TxnType.TRANSFER) {
            if (r.accountId == null || r.toAccountId == null) throw badRequest("TRANSFER_ACCOUNTS", "Transfers need a source and destination account")
            if (r.accountId == r.toAccountId) throw badRequest("TRANSFER_ACCOUNTS", "Source and destination must differ")
        } else if (r.toAccountId != null) {
            throw badRequest("TO_ACCOUNT", "Only transfers have a destination account")
        }

        if (r.loanId != null) loans.findByIdAndFamilyId(r.loanId, familyId) ?: throw notFound("Loan")

        if (r.type == TxnType.REFUND) {
            if (r.accountId == null) throw badRequest("REFUND_ACCOUNT", "Choose the account that received the refund")
            if (r.commitment != null || r.loanId != null) throw badRequest("REFUND_FIELDS", "Refunds cannot have a commitment or loan")
        } else if (r.refundOfTxnId != null) {
            throw badRequest("REFUND_OF", "Only refunds can refer to an original expense")
        }
        val isExpenseLike = r.type == TxnType.EXPENSE || r.type == TxnType.REFUND
        var necessity = if (isExpenseLike) r.necessity else null
        if (r.categoryId != null) {
            val cat = categories.findByIdAndFamilyId(r.categoryId, familyId) ?: throw notFound("Category")
            val expected = when (r.type) {
                TxnType.INCOME -> CategoryType.INCOME
                TxnType.EXPENSE, TxnType.REFUND -> CategoryType.EXPENSE
                TxnType.TRANSFER -> throw badRequest("CATEGORY", "Transfers cannot have a category")
            }
            if (cat.type != expected) throw badRequest("CATEGORY", "Category type does not match transaction type")
            if (necessity == null) necessity = cat.defaultNecessity
        }

        val rate = (r.fxRate ?: currency.rateToBase(familyId, family.baseCurrency, r.currency, r.date)).setScale(8, java.math.RoundingMode.HALF_UP)
        val baseAmount = (r.amount * rate).money()
        if (r.refundOfTxnId != null) checkRefundOf(id, familyId, r.refundOfTxnId, baseAmount)
        return Txn(
            id = id, familyId = familyId, userId = userId, type = r.type, amount = r.amount.money(),
            currency = r.currency.uppercase(), fxRate = rate, baseAmount = baseAmount, date = r.date,
            accountId = r.accountId, toAccountId = r.toAccountId, categoryId = r.categoryId,
            commitment = if (r.type == TxnType.EXPENSE) r.commitment ?: Commitment.ONE_TIME else null,
            necessity = necessity, note = r.note, importHash = r.importHash, loanId = if (r.type == TxnType.EXPENSE) r.loanId else null,
            excluded = r.excluded, refundOfTxnId = r.refundOfTxnId,
        )
    }

    /** The refunded entry must be an expense of this family and total refunds may not exceed it. */
    private fun checkRefundOf(selfId: Long?, familyId: Long, originalId: Long, baseAmount: java.math.BigDecimal) {
        val original = repo.findByIdAndFamilyId(originalId, familyId)?.takeIf { it.type == TxnType.EXPENSE } ?: throw notFound("Original expense")
        val others = jdbc.sql("SELECT COALESCE(sum(base_amount), 0) FROM txn WHERE refund_of_txn_id = :o AND (CAST(:self AS bigint) IS NULL OR id <> :self)")
            .param("o", originalId).param("self", selfId, java.sql.Types.BIGINT).query(java.math.BigDecimal::class.java).single()
        if (others + baseAmount > original.baseAmount) throw badRequest("REFUND_EXCEEDS", "Refunds exceed the original expense")
    }
}
