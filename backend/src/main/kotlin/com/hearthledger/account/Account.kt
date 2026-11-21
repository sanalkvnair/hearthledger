package com.hearthledger.account

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.CrudRepository
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import java.math.BigDecimal

enum class AccountType {
    BANK, CASH, CREDIT_CARD, SAVINGS, FIXED_DEPOSIT, INVESTMENT, LOAN;

    val isSavings get() = this == SAVINGS || this == FIXED_DEPOSIT || this == INVESTMENT
}

/** PRIVATE: one owner, JOINT: two or more selected members, FAMILY: shared by everyone. */
enum class Ownership { PRIVATE, JOINT, FAMILY }

@Table("account")
data class Account(
    @Id val id: Long? = null,
    val familyId: Long,
    val name: String,
    val type: AccountType,
    val currency: String,
    val openingBalance: BigDecimal = BigDecimal.ZERO,
    val institution: String? = null,
    val archived: Boolean = false,
    val ownership: Ownership = Ownership.FAMILY,
    /** Credit cards: day of month the statement closes, and the day the bill is due. */
    val statementDay: Int? = null,
    val dueDay: Int? = null,
)

interface AccountRepository : CrudRepository<Account, Long> {
    fun findByFamilyId(familyId: Long): List<Account>
    fun findByIdAndFamilyId(id: Long, familyId: Long): Account?
}

@Service
class AccountOwners(private val jdbc: JdbcClient) {
    /** account id -> owner user ids for the whole family. */
    fun byAccount(familyId: Long): Map<Long, List<Long>> =
        jdbc.sql("SELECT o.account_id, o.user_id FROM account_owner o JOIN account a ON a.id = o.account_id WHERE a.family_id = :f ORDER BY o.user_id")
            .param("f", familyId).query { rs, _ -> rs.getLong(1) to rs.getLong(2) }.list().groupBy({ it.first }, { it.second })

    fun set(accountId: Long, owners: List<Long>) {
        jdbc.sql("DELETE FROM account_owner WHERE account_id = :a").param("a", accountId).update()
        owners.distinct().forEach {
            jdbc.sql("INSERT INTO account_owner (account_id, user_id) VALUES (:a, :u)").param("a", accountId).param("u", it).update()
        }
    }
}
