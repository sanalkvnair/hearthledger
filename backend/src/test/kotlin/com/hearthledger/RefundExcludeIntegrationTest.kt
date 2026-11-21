package com.hearthledger

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.hearthledger.user.AppUser
import com.hearthledger.user.AppUserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDate

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class RefundExcludeIntegrationTest(@Autowired val mvc: MockMvc, @Autowired val users: AppUserRepository) {
    private val mapper = ObjectMapper()
    private val today = LocalDate.now()

    private fun user(n: String) = users.save(AppUser(email = "$n@rx.example.com", name = n, googleSub = "rx-$n-${System.nanoTime()}"))
    private fun send(u: AppUser, method: String, url: String, body: Map<String, Any?> = emptyMap()): ResultActions =
        mvc.perform(
            (if (method == "PUT") put(url) else post(url)).with(jwt().jwt { it.subject(u.id.toString()) })
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)),
        )
    private fun getJson(u: AppUser, url: String) = mvc.perform(get(url).with(jwt().jwt { it.subject(u.id.toString()) }))
    private fun tree(r: ResultActions): JsonNode = mapper.readTree(r.andReturn().response.contentAsString)

    private fun setup(u: AppUser) {
        send(u, "POST", "/api/v1/families", mapOf("name" to "F", "baseCurrency" to "EUR")).andExpect(status().isCreated)
    }
    private fun account(u: AppUser, name: String, type: String) =
        tree(send(u, "POST", "/api/v1/accounts", mapOf("name" to name, "type" to type, "currency" to "EUR")))["id"].asLong()
    private fun category(u: AppUser, name: String, type: String, exclude: Boolean = false) =
        tree(send(u, "POST", "/api/v1/categories", mapOf("name" to name, "type" to type, "excludeFromReports" to exclude)))["id"].asLong()
    private fun txn(u: AppUser, type: String, amount: Int, account: Long, extra: Map<String, Any?> = emptyMap()) =
        send(u, "POST", "/api/v1/transactions", mapOf("type" to type, "amount" to amount, "currency" to "EUR", "date" to today.toString(), "accountId" to account) + extra)

    @Test
    fun `refunds reduce expenses, are validated and cannot exceed the original`() {
        val u = user("refund"); setup(u)
        val card = account(u, "Card", "CREDIT_CARD"); val bank = account(u, "Bank", "BANK")
        val medical = category(u, "Medical", "EXPENSE"); val salary = category(u, "Salary", "INCOME")
        val expense = tree(txn(u, "EXPENSE", 100, card, mapOf("categoryId" to medical)).andExpect(status().isCreated))["id"].asLong()

        val refund = tree(txn(u, "REFUND", 60, bank, mapOf("categoryId" to medical, "refundOfTxnId" to expense)).andExpect(status().isCreated))["id"].asLong()
        txn(u, "REFUND", 50, bank, mapOf("refundOfTxnId" to expense)).andExpect(status().isUnprocessableEntity).andExpect(jsonPath("$.code").value("REFUND_EXCEEDS"))
        txn(u, "REFUND", 40, bank, mapOf("categoryId" to medical, "refundOfTxnId" to expense)).andExpect(status().isCreated)
        txn(u, "REFUND", 10, bank, mapOf("categoryId" to salary)).andExpect(status().isUnprocessableEntity)
        txn(u, "REFUND", 10, bank, mapOf("toAccountId" to card)).andExpect(status().isUnprocessableEntity)
        txn(u, "EXPENSE", 10, bank, mapOf("refundOfTxnId" to expense)).andExpect(status().isUnprocessableEntity)
        // editing a refund does not count itself against the limit
        send(u, "PUT", "/api/v1/transactions/$refund", mapOf("type" to "REFUND", "amount" to 60, "currency" to "EUR", "date" to today.toString(), "accountId" to bank, "categoryId" to medical, "refundOfTxnId" to expense))
            .andExpect(status().isOk)

        val dash = tree(getJson(u, "/api/v1/dashboard?date=$today"))
        assertEquals(0, dash["expense"].decimalValue().compareTo(java.math.BigDecimal.ZERO))
        assertEquals(0, dash["income"].decimalValue().compareTo(java.math.BigDecimal.ZERO))
        assertEquals(0, dash["expenseByCategory"].size())
        getJson(u, "/api/v1/transactions?type=REFUND").andExpect(jsonPath("$.total").value(2))
        getJson(u, "/api/v1/transactions?type=EXPENSE").andExpect(jsonPath("$.items[0].refundedAmount").value(100.0))
    }

    @Test
    fun `excluded entries and categories are left out of reports but still listed`() {
        val u = user("exclude"); setup(u)
        val bank = account(u, "Bank", "BANK")
        val food = category(u, "Food", "EXPENSE"); val pass = category(u, "Pass-through", "EXPENSE", exclude = true)
        val sub = tree(send(u, "POST", "/api/v1/categories", mapOf("name" to "Sub", "type" to "EXPENSE", "parentId" to pass)))["id"].asLong()

        txn(u, "EXPENSE", 30, bank, mapOf("categoryId" to food)).andExpect(status().isCreated)
        txn(u, "EXPENSE", 70, bank, mapOf("categoryId" to food, "excluded" to true)).andExpect(status().isCreated)
        txn(u, "EXPENSE", 25, bank, mapOf("categoryId" to pass)).andExpect(status().isCreated)
        txn(u, "EXPENSE", 15, bank, mapOf("categoryId" to sub)).andExpect(status().isCreated)

        val dash = tree(getJson(u, "/api/v1/dashboard?date=$today"))
        assertEquals(0, dash["expense"].decimalValue().compareTo(java.math.BigDecimal(30)))
        assertEquals(1, dash["expenseByCategory"].size())
        getJson(u, "/api/v1/transactions").andExpect(jsonPath("$.total").value(4))
        getJson(u, "/api/v1/transactions?excluded=true").andExpect(jsonPath("$.total").value(3))
            .andExpect(jsonPath("$.items[0].effectivelyExcluded").value(true))
        getJson(u, "/api/v1/transactions?excluded=false").andExpect(jsonPath("$.total").value(1))
    }

    @Test
    fun `imported incoming rows can be committed as refunds`() {
        val u = user("import-refund"); setup(u)
        val bank = account(u, "Bank", "BANK"); val medical = category(u, "Medical", "EXPENSE")
        send(u, "POST", "/api/v1/import/commit", mapOf("accountId" to bank, "rows" to listOf(
            mapOf("date" to today.toString(), "description" to "Insurance payout", "amount" to 80, "type" to "REFUND", "categoryId" to medical),
        ))).andExpect(status().isOk)
        getJson(u, "/api/v1/transactions?type=REFUND").andExpect(jsonPath("$.total").value(1))
        val dash = tree(getJson(u, "/api/v1/dashboard?date=$today"))
        assertEquals(0, dash["expense"].decimalValue().compareTo(java.math.BigDecimal(-80)))
        assertEquals(0, dash["income"].decimalValue().compareTo(java.math.BigDecimal.ZERO))
    }
}
