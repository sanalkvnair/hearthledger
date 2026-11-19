package com.openfamilybudget

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openfamilybudget.user.AppUser
import com.openfamilybudget.user.AppUserRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDate

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class Phase2IntegrationTest(@Autowired val mvc: MockMvc, @Autowired val users: AppUserRepository) {
    private val mapper = ObjectMapper()
    private val today = LocalDate.now()

    private fun user(n: String) = users.save(AppUser(email = "$n@p2.example.com", name = n, googleSub = "p2-$n-${System.nanoTime()}"))
    private fun json(u: AppUser, method: String, url: String, body: Map<String, Any?>? = null) =
        mvc.perform(
            (if (method == "PUT") put(url) else post(url)).with(jwt().jwt { it.subject(u.id.toString()) })
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body ?: emptyMap<String, Any>())),
        )
    private fun getJson(u: AppUser, url: String) = mvc.perform(get(url).with(jwt().jwt { it.subject(u.id.toString()) }))
    private fun tree(r: org.springframework.test.web.servlet.ResultActions): JsonNode = mapper.readTree(r.andReturn().response.contentAsString)

    @Test
    fun `recurring rules, loans, savings, budgets and buffer`() {
        val u = user("p2")
        json(u, "POST", "/api/v1/families", mapOf("name" to "F", "baseCurrency" to "EUR")).andExpect(status().isCreated)
        val bank = tree(json(u, "POST", "/api/v1/accounts", mapOf("name" to "Bank", "type" to "BANK", "currency" to "EUR")))["id"].asLong()
        val savings = tree(json(u, "POST", "/api/v1/accounts", mapOf("name" to "Rainy", "type" to "SAVINGS", "currency" to "EUR", "openingBalance" to 1000, "ownerUserId" to u.id)))["id"].asLong()
        val categories = tree(getJson(u, "/api/v1/categories"))
        val groceries = categories.first { it["name"].asText() == "Groceries" }["id"].asLong()
        val food = categories.first { it["name"].asText() == "Food" }["id"].asLong()

        // monthly rule starting two months ago -> 3 generated entries, and re-running adds none
        val rule = tree(
            json(u, "POST", "/api/v1/recurring-rules", mapOf("type" to "EXPENSE", "amount" to 50, "currency" to "EUR", "frequency" to "MONTHLY", "startDate" to today.minusMonths(2).toString(), "accountId" to bank, "note" to "Gym")),
        )
        getJson(u, "/api/v1/transactions?type=EXPENSE").andExpect(jsonPath("$.total").value(3))
        json(u, "PUT", "/api/v1/recurring-rules/${rule["id"].asLong()}", mapOf("type" to "EXPENSE", "amount" to 50, "currency" to "EUR", "frequency" to "MONTHLY", "startDate" to today.minusMonths(2).toString(), "accountId" to bank))
            .andExpect(status().isOk)
        getJson(u, "/api/v1/transactions?type=EXPENSE").andExpect(jsonPath("$.total").value(3))

        // 12-month 0% loan: EMI 100, first instalment 1 month ago -> 2 instalments booked, 10 remaining
        val loan = tree(json(u, "POST", "/api/v1/loans", mapOf("name" to "Phone", "currency" to "EUR", "principal" to 1200, "interestRate" to 0, "tenureMonths" to 12, "startDate" to today.minusMonths(1).toString(), "accountId" to bank)))
        org.junit.jupiter.api.Assertions.assertEquals(2, loan["paidInstalments"].asInt())
        org.junit.jupiter.api.Assertions.assertEquals("SHORT_TERM", loan["term"].asText())
        getJson(u, "/api/v1/loans").andExpect(jsonPath("$[0].remainingBalance").value(1000.0))
        getJson(u, "/api/v1/loans/${loan["id"].asLong()}/schedule").andExpect(jsonPath("$.length()").value(12))

        // transfers into a savings account show up on the savings overview
        json(u, "POST", "/api/v1/transactions", mapOf("type" to "TRANSFER", "amount" to 250, "currency" to "EUR", "date" to today.toString(), "accountId" to bank, "toAccountId" to savings)).andExpect(status().isCreated)
        getJson(u, "/api/v1/savings")
            .andExpect(jsonPath("$.accounts[0].contributed").value(1250.0))
            .andExpect(jsonPath("$.totalValue").value(1250.0))
            .andExpect(jsonPath("$.byMember[0].userId").value(u.id!!))
        json(u, "PUT", "/api/v1/savings/$savings/details", mapOf("currentValue" to 1400, "interestRate" to 3.5)).andExpect(status().isOk)
        getJson(u, "/api/v1/savings").andExpect(jsonPath("$.accounts[0].gain").value(150.0))

        // budget progress for a parent category includes its subcategories
        json(u, "POST", "/api/v1/transactions", mapOf("type" to "EXPENSE", "amount" to 90, "currency" to "EUR", "date" to today.toString(), "categoryId" to groceries)).andExpect(status().isCreated)
        json(u, "PUT", "/api/v1/budgets", mapOf("categoryId" to food, "amount" to 100)).andExpect(status().isOk)
        getJson(u, "/api/v1/budgets")
            .andExpect(jsonPath("$[0].spent").value(90.0))
            .andExpect(jsonPath("$[0].status").value("WARNING"))

        // buffer: gym 50 + EMI 100 (+ no completed-period essential spend); 6 months = 900
        getJson(u, "/api/v1/buffer")
            .andExpect(jsonPath("$.recurringMonthly").value(50.0))
            .andExpect(jsonPath("$.loanMonthly").value(100.0))
            .andExpect(jsonPath("$.requirements[?(@.months==6)].amount").value(900.0))
            .andExpect(jsonPath("$.liquidSavings").value(1400.0))
        getJson(u, "/api/v1/buffer?userId=${u.id}").andExpect(jsonPath("$.loanMonthly").value(0.0))
    }
}
