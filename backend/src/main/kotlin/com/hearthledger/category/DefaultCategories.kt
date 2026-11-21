package com.hearthledger.category

import com.hearthledger.common.Necessity
import com.hearthledger.common.Necessity.*
import com.hearthledger.family.FamilyCreated
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class DefaultCategories(private val repo: CategoryRepository) {

    private val expense: Map<String, Pair<Necessity, List<String>>> = linkedMapOf(
        "Housing" to (ESSENTIAL to listOf("Rent / Mortgage", "Utilities", "Maintenance", "Internet & Phone")),
        "Food" to (ESSENTIAL to listOf("Groceries", "Dining out")),
        "Transport" to (IMPORTANT to listOf("Fuel", "Public transport", "Car maintenance")),
        "Health" to (ESSENTIAL to listOf("Doctor", "Pharmacy", "Insurance")),
        "Education" to (IMPORTANT to listOf("School fees", "Courses")),
        "Loans & EMI" to (ESSENTIAL to listOf("Home loan", "Car loan", "Personal loan")),
        "Entertainment" to (DISCRETIONARY to listOf("Subscriptions", "Travel", "Hobbies")),
        "Shopping" to (DISCRETIONARY to listOf("Clothing", "Electronics", "Gifts")),
    )

    private val income = mapOf(
        "Salary" to listOf("Primary salary", "Bonus"),
        "Business" to emptyList(),
        "Investments" to listOf("Interest", "Dividends"),
        "Other income" to emptyList(),
    )

    @EventListener
    fun seed(e: FamilyCreated) {
        expense.forEach { (name, v) ->
            val parent = repo.save(Category(familyId = e.familyId, type = CategoryType.EXPENSE, name = name, defaultNecessity = v.first))
            v.second.forEach {
                repo.save(Category(familyId = e.familyId, parentId = parent.id, type = CategoryType.EXPENSE, name = it, defaultNecessity = v.first))
            }
        }
        income.forEach { (name, subs) ->
            val parent = repo.save(Category(familyId = e.familyId, type = CategoryType.INCOME, name = name))
            subs.forEach { repo.save(Category(familyId = e.familyId, parentId = parent.id, type = CategoryType.INCOME, name = it)) }
        }
    }
}
