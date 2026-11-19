package com.openfamilybudget.user

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.CrudRepository
import java.time.Instant

@Table("app_user")
data class AppUser(
    @Id val id: Long? = null,
    val email: String,
    val name: String,
    val avatarUrl: String? = null,
    val googleSub: String,
    val locale: String = "en",
    val theme: String = "auto",
    val defaultExpenseAccountId: Long? = null,
    val defaultIncomeAccountId: Long? = null,
    val defaultSavingsSourceAccountId: Long? = null,
    val createdAt: Instant = Instant.now(),
)

interface AppUserRepository : CrudRepository<AppUser, Long> {
    fun findByGoogleSub(googleSub: String): AppUser?
}
