package com.hearthledger

import com.fasterxml.jackson.databind.JsonNode
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
class ReviewFixesTest(@Autowired val mvc: MockMvc, @Autowired val users: AppUserRepository) {
    private val mapper = ObjectMapper()
    private val today = LocalDate.now()

    private fun user(n: String) = users.save(AppUser(email = "$n@rf.example.com", name = n, googleSub = "rf-$n-${System.nanoTime()}"))
    private fun send(u: AppUser, method: String, url: String, body: Map<String, Any?> = emptyMap()): ResultActions =
        mvc.perform(
            (if (method == "PUT") put(url) else post(url)).with(jwt().jwt { it.subject(u.id.toString()) })
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)),
        )
    private fun getJson(u: AppUser, url: String) = mvc.perform(get(url).with(jwt().jwt { it.subject(u.id.toString()) }))
    private fun tree(r: ResultActions): JsonNode = mapper.readTree(r.andReturn().response.contentAsString)

    private fun family(u: AppUser) = send(u, "POST", "/api/v1/families", mapOf("name" to "F", "baseCurrency" to "EUR")).andExpect(status().isCreated)

    @Test
    fun `references to another family are rejected`() {
        val a = user("a"); val b = user("b")
        family(a); family(b)
        val foreignAccount = tree(send(b, "POST", "/api/v1/accounts", mapOf("name" to "B", "type" to "BANK", "currency" to "EUR")))["id"].asLong()
        val foreignCategory = tree(getJson(b, "/api/v1/categories"))[0]["id"].asLong()

        send(a, "POST", "/api/v1/accounts", mapOf("name" to "X", "type" to "BANK", "currency" to "EUR", "ownerUserId" to b.id)).andExpect(status().isNotFound)
        val loan = mapOf("name" to "L", "currency" to "EUR", "principal" to 1200, "interestRate" to 0, "tenureMonths" to 12, "startDate" to today.toString())
        send(a, "POST", "/api/v1/loans", loan + ("accountId" to foreignAccount)).andExpect(status().isNotFound)
        send(a, "POST", "/api/v1/loans", loan + ("categoryId" to foreignCategory)).andExpect(status().isNotFound)
        send(a, "POST", "/api/v1/loans", loan + ("ownerUserId" to b.id)).andExpect(status().isNotFound)
    }

    @Test
    fun `changing a recurring rule's frequency restarts numbering without skipping`() {
        val u = user("rec"); family(u)
        val start = today.minusDays(14).toString()
        val rule = tree(send(u, "POST", "/api/v1/recurring-rules", mapOf("type" to "EXPENSE", "amount" to 10, "currency" to "EUR", "frequency" to "MONTHLY", "startDate" to start)))
        getJson(u, "/api/v1/transactions?type=EXPENSE").andExpect(jsonPath("$.total").value(1))
        send(u, "PUT", "/api/v1/recurring-rules/${rule["id"].asLong()}", mapOf("type" to "EXPENSE", "amount" to 10, "currency" to "EUR", "frequency" to "WEEKLY", "startDate" to start))
            .andExpect(status().isOk)
        // start, +1 week, +2 weeks
        getJson(u, "/api/v1/transactions?type=EXPENSE").andExpect(jsonPath("$.total").value(3))
    }

    @Test
    fun `invalid fx rate and mismatched invitation email are rejected`() {
        val owner = user("own"); family(owner)
        send(owner, "POST", "/api/v1/transactions", mapOf("type" to "EXPENSE", "amount" to 5, "currency" to "USD", "fxRate" to 0, "date" to today.toString())).andExpect(status().isBadRequest)

        val code = tree(send(owner, "POST", "/api/v1/family/invitations", mapOf("email" to "someone@rf.example.com", "role" to "MEMBER")))["code"].asText()
        val other = user("other")
        send(other, "POST", "/api/v1/invitations/$code/accept").andExpect(status().isForbidden)
        val right = users.save(AppUser(email = "someone@rf.example.com", name = "S", googleSub = "rf-s-${System.nanoTime()}"))
        send(right, "POST", "/api/v1/invitations/$code/accept").andExpect(status().isOk)
        val third = user("third")
        send(third, "POST", "/api/v1/invitations/$code/accept").andExpect(status().isGone)
    }

    @Test
    fun `savings overview fails loudly when an exchange rate is missing`() {
        val u = user("fx"); family(u)
        send(u, "POST", "/api/v1/accounts", mapOf("name" to "Yen", "type" to "SAVINGS", "currency" to "JPY", "openingBalance" to 1000000)).andExpect(status().isCreated)
        getJson(u, "/api/v1/savings").andExpect(status().isUnprocessableEntity)
    }
}
