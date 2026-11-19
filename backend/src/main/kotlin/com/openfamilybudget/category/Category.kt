package com.openfamilybudget.category

import com.openfamilybudget.common.Necessity
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.CrudRepository

enum class CategoryType { INCOME, EXPENSE }

@Table("category")
data class Category(
    @Id val id: Long? = null,
    val familyId: Long,
    val parentId: Long? = null,
    val type: CategoryType,
    val name: String,
    val icon: String? = null,
    val color: String? = null,
    val defaultNecessity: Necessity? = null,
    val archived: Boolean = false,
)

interface CategoryRepository : CrudRepository<Category, Long> {
    fun findByFamilyId(familyId: Long): List<Category>
    fun findByIdAndFamilyId(id: Long, familyId: Long): Category?
}
