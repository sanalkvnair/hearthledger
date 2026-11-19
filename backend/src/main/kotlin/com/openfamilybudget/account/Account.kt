package com.openfamilybudget.account

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.CrudRepository
import java.math.BigDecimal

enum class AccountType {
    BANK, CASH, CREDIT_CARD, SAVINGS, FIXED_DEPOSIT, INVESTMENT, LOAN;

    val isSavings get() = this == SAVINGS || this == FIXED_DEPOSIT || this == INVESTMENT
}

@Table("account")
data class Account(
    @Id val id: Long? = null,
    val familyId: Long,
    val ownerUserId: Long? = null,
    val name: String,
    val type: AccountType,
    val currency: String,
    val openingBalance: BigDecimal = BigDecimal.ZERO,
    val institution: String? = null,
    val archived: Boolean = false,
)

interface AccountRepository : CrudRepository<Account, Long> {
    fun findByFamilyId(familyId: Long): List<Account>
    fun findByIdAndFamilyId(id: Long, familyId: Long): Account?
}
