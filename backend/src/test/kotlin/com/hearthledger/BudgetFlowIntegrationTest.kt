package com.hearthledger

import com.fasterxml.jackson.databind.ObjectMapper
import com.hearthledger.user.AppUser
import com.hearthledger.user.AppUserRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.request.RequestPostProcessor

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class BudgetFlowIntegrationTest(
    @Autowired val mvc: MockMvc,
    @Autowired val users: AppUserRepository,
) {
    private val mapper = ObjectMapper()

    private fun user(n: String) = users.save(AppUser(email = "$n@example.com", name = n, googleSub = "sub-$n-${System.nanoTime()}"))
    private fun auth(u: AppUser): RequestPostProcessor = jwt().jwt { it.subject(u.id.toString()) }

    private fun post(u: AppUser, url: String, body: Map<String, Any?>) =
        mvc.perform(post(url).with(auth(u)).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))

    private fun id(json: String) = mapper.readTree(json).get("id").asLong()

    @Test
    fun `full flow - totals, carry-over, overrides and family isolation`() {
        val alice = user("alice")
        val bob = user("bob")

        mvc.perform(get("/api/v1/transactions")).andExpect(status().isUnauthorized)
        mvc.perform(get("/api/v1/transactions").with(auth(alice))).andExpect(status().isConflict)

        post(alice, "/api/v1/families", mapOf("name" to "Smith", "baseCurrency" to "EUR", "cycleStartDay" to 25)).andExpect(status().isCreated)
        mvc.perform(get("/api/v1/categories").with(auth(alice))).andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThan(10)))

        val bank = id(post(alice, "/api/v1/accounts", mapOf("name" to "Bank", "type" to "BANK", "currency" to "EUR")).andReturn().response.contentAsString)
        val savings = id(post(alice, "/api/v1/accounts", mapOf("name" to "Rainy day", "type" to "SAVINGS", "currency" to "EUR")).andReturn().response.contentAsString)

        fun txn(type: String, amount: Int, date: String, extra: Map<String, Any?> = emptyMap()) =
            post(alice, "/api/v1/transactions", mapOf("type" to type, "amount" to amount, "currency" to "EUR", "date" to date, "accountId" to bank) + extra)

        txn("INCOME", 3000, "2026-01-26").andExpect(status().isCreated)
        txn("EXPENSE", 500, "2026-02-01", mapOf("commitment" to "LONG_TERM_LOAN")).andExpect(status().isCreated)
        txn("TRANSFER", 200, "2026-02-02", mapOf("toAccountId" to savings)).andExpect(status().isCreated)

        mvc.perform(get("/api/v1/dashboard").param("date", "2026-02-01").with(auth(alice)))
            .andExpect(jsonPath("$.period.start").value("2026-01-25"))
            .andExpect(jsonPath("$.period.end").value("2026-02-24"))
            .andExpect(jsonPath("$.income").value(3000.0))
            .andExpect(jsonPath("$.expense").value(500.0))
            .andExpect(jsonPath("$.savings").value(200.0))
            .andExpect(jsonPath("$.balanceWithoutPrevious").value(2300.0))
            .andExpect(jsonPath("$.openingBalance.effective").value(0.0))

        // next period carries the computed balance, which the user can override with the actual amount
        mvc.perform(get("/api/v1/dashboard").param("date", "2026-02-25").with(auth(alice)))
            .andExpect(jsonPath("$.openingBalance.effective").value(2300.0))
            .andExpect(jsonPath("$.balanceWithPrevious").value(2300.0))
        mvc.perform(put("/api/v1/periods/2026-02-25/opening-balance").with(auth(alice)).contentType(MediaType.APPLICATION_JSON).content("""{"amount": 2000}"""))
            .andExpect(status().isOk)
        mvc.perform(get("/api/v1/dashboard").param("date", "2026-02-25").with(auth(alice)))
            .andExpect(jsonPath("$.openingBalance.computed").value(2300.0))
            .andExpect(jsonPath("$.openingBalance.effective").value(2000.0))

        // foreign currency needs an exchange rate
        post(alice, "/api/v1/transactions", mapOf("type" to "EXPENSE", "amount" to 10, "currency" to "USD", "date" to "2026-02-03"))
            .andExpect(status().isUnprocessableEntity)
        post(alice, "/api/v1/exchange-rates", mapOf("fromCurrency" to "USD", "rate" to 0.9, "effectiveDate" to "2026-01-01")).andExpect(status().isCreated)
        post(alice, "/api/v1/transactions", mapOf("type" to "EXPENSE", "amount" to 10, "currency" to "USD", "date" to "2026-02-03"))
            .andExpect(status().isCreated).andExpect(jsonPath("$.baseAmount").value(9.0))

        // another family sees none of this data
        post(bob, "/api/v1/families", mapOf("name" to "Jones", "baseCurrency" to "SEK")).andExpect(status().isCreated)
        mvc.perform(get("/api/v1/transactions").with(auth(bob))).andExpect(jsonPath("$.total").value(0))
    }

    @Test
    fun `invitation lets a viewer join but not write`() {
        val owner = user("owner")
        val viewer = user("viewer")
        post(owner, "/api/v1/families", mapOf("name" to "Fam", "baseCurrency" to "EUR")).andExpect(status().isCreated)
        val code = mapper.readTree(post(owner, "/api/v1/family/invitations", mapOf("role" to "VIEWER")).andExpect(status().isCreated).andReturn().response.contentAsString).get("code").asText()

        mvc.perform(post("/api/v1/invitations/$code/accept").with(auth(viewer))).andExpect(status().isOk)
        mvc.perform(get("/api/v1/family/members").with(auth(viewer))).andExpect(jsonPath("$.length()").value(2))
        post(viewer, "/api/v1/accounts", mapOf("name" to "x", "type" to "CASH", "currency" to "EUR")).andExpect(status().isForbidden)
        mvc.perform(post("/api/v1/invitations/$code/accept").with(auth(user("late")))).andExpect(status().isGone)
    }
}
