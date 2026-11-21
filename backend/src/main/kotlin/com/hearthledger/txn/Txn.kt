package com.hearthledger.txn

import com.hearthledger.common.Commitment
import com.hearthledger.common.Necessity
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.CrudRepository
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

enum class TxnType { INCOME, EXPENSE, TRANSFER, REFUND }

@Table("txn")
data class Txn(
    @Id val id: Long? = null,
    val familyId: Long,
    val userId: Long,
    val type: TxnType,
    val amount: BigDecimal,
    val currency: String,
    val fxRate: BigDecimal,
    val baseAmount: BigDecimal,
    val date: LocalDate,
    val accountId: Long? = null,
    val toAccountId: Long? = null,
    val categoryId: Long? = null,
    val commitment: Commitment? = null,
    val necessity: Necessity? = null,
    val note: String? = null,
    val loanId: Long? = null,
    val recurringRuleId: Long? = null,
    val importHash: String? = null,
    val loanInterestPart: BigDecimal? = null,
    val loanPrincipalPart: BigDecimal? = null,
    val loanScheduleNo: Int? = null,
    val excluded: Boolean = false,
    val refundOfTxnId: Long? = null,
    val createdAt: Instant = Instant.now(),
)

interface TxnRepository : CrudRepository<Txn, Long> {
    fun findByIdAndFamilyId(id: Long, familyId: Long): Txn?
}
