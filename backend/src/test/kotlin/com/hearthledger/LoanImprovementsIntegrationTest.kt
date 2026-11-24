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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class LoanImprovementsIntegrationTest(@Autowired val mvc: MockMvc, @Autowired val users: AppUserRepository) {
    private val mapper = ObjectMapper()

    private fun user(n: String) = users.save(AppUser(email = "$n@li.example.com", name = n, googleSub = "li-$n-${System.nanoTime()}"))
    private fun send(u: AppUser, method: String, url: String, body: Map<String, Any?> = emptyMap()): ResultActions =
        mvc.perform(
            (if (method == "PUT") put(url) else post(url)).with(jwt().jwt { it.subject(u.id.toString()) })
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)),
        )
    private fun getJson(u: AppUser, url: String) = mvc.perform(get(url).with(jwt().jwt { it.subject(u.id.toString()) }))
    private fun tree(r: ResultActions): JsonNode = mapper.readTree(r.andReturn().response.contentAsString)
    private fun setup(u: AppUser) {
        send(u, "POST", "/api/v1/families", mapOf("name" to "F", "baseCurrency" to "SEK")).andExpect(status().isCreated)
    }

    private val rates = listOf(
        "2024-10-29" to 4.13, "2025-02-04" to 3.13, "2025-08-04" to 2.93, "2025-10-29" to 2.65, "2026-05-05" to 2.75,
    )

    private fun reference(extra: Map<String, Any?>) = mapOf(
        "name" to "Ref", "currency" to "SEK", "principal" to 1105000, "interestRate" to 4.94, "startDate" to "2023-12-28",
        "disbursedOn" to "2023-11-01", "rateFixedUntil" to "2024-11-01",
    ) + extra

    @Test
    fun `fixed amortization reference loan - late-entered rate changes correct booked payments`() {
        val u = user("fa"); setup(u)
        val loan = tree(send(u, "POST", "/api/v1/loans", reference(mapOf("loanType" to "FIXED_AMORTIZATION", "amortizationAmount" to 3683, "tenureMonths" to 360))).andExpect(status().isCreated))
        val id = loan["id"].asLong()
        rates.forEach { (d, r) -> send(u, "POST", "/api/v1/loans/$id/rates", mapOf("effectiveFrom" to d, "annualRate" to r)).andExpect(status().isCreated) }

        val row = tree(getJson(u, "/api/v1/loans/$id/schedule"))[35]
        assertEquals("2026-11-28", row["date"].asText())
        assertEquals(2237, Math.round(row["interest"].asDouble()))
        assertEquals(5920, Math.round(row["payment"].asDouble()))

        val dto = tree(getJson(u, "/api/v1/loans"))[0]
        assertEquals(2.75, dto["interestRate"].asDouble(), 0.0001)
        assertEquals(360, dto["termMonths"].asInt())
        assertEquals("2023-11-01", dto["disbursedOn"].asText())

        // booked payment #12 (2024-11-28) now pays the 4.13% interest, not the initial 4.94%
        val booked = tree(getJson(u, "/api/v1/transactions?type=EXPENSE&size=200"))["items"].first { it["date"].asText() == "2024-11-28" }
        assertEquals(Math.round(3683 + (1105000 - 3683.0 * 11) * 0.0413 / 12), Math.round(booked["amount"].asDouble()))
    }

    @Test
    fun `interest only reference loan with an optional term`() {
        val u = user("io"); setup(u)
        val loan = tree(send(u, "POST", "/api/v1/loans", reference(mapOf("loanType" to "INTEREST_ONLY", "tenureMonths" to 720, "interestRate" to 4.61))).andExpect(status().isCreated))
        val id = loan["id"].asLong()
        listOf("2025-10-29" to 2.65, "2026-05-05" to 2.75).forEach { (d, r) -> send(u, "POST", "/api/v1/loans/$id/rates", mapOf("effectiveFrom" to d, "annualRate" to r)) }
        val row = tree(getJson(u, "/api/v1/loans/$id/schedule"))[35]
        assertEquals(2532, Math.round(row["interest"].asDouble()))
        assertEquals(0.0, row["principal"].asDouble(), 0.0)
        assertEquals(720, tree(getJson(u, "/api/v1/loans"))[0]["tenureMonths"].asInt())
        send(u, "POST", "/api/v1/loans/preview", reference(mapOf("loanType" to "FIXED_AMORTIZATION", "amortizationAmount" to 3683, "amortizationRate" to 2, "tenureMonths" to 360)))
            .andExpect(status().isUnprocessableEntity)
    }

    @Test
    fun `loans can be edited, closed, reopened and deleted with or without payments`() {
        val u = user("edit"); setup(u); val other = user("edit-other"); setup(other)
        val body = mapOf("name" to "Phone", "currency" to "SEK", "principal" to 1200, "interestRate" to 0, "tenureMonths" to 12, "startDate" to java.time.LocalDate.now().minusMonths(2).toString())
        val id = tree(send(u, "POST", "/api/v1/loans", body))["id"].asLong()
        getJson(u, "/api/v1/transactions?type=EXPENSE").andExpect(jsonPath("$.total").value(3))

        val edited = tree(send(u, "PUT", "/api/v1/loans/$id", body + mapOf("principal" to 2400, "name" to "Phone 2")).andExpect(status().isOk))
        assertEquals("Phone 2", edited["name"].asText())
        assertEquals(3, edited["paidInstalments"].asInt())
        getJson(u, "/api/v1/transactions?type=EXPENSE").andExpect(jsonPath("$.items[0].amount").value(200.0))

        // shortening the loan below what is already booked removes the surplus payments
        send(u, "PUT", "/api/v1/loans/$id", body + mapOf("tenureMonths" to 2)).andExpect(status().isOk)
        getJson(u, "/api/v1/transactions?type=EXPENSE").andExpect(jsonPath("$.total").value(2))

        mvc.perform(post("/api/v1/loans/$id/close").with(jwt().jwt { it.subject(u.id.toString()) })).andExpect(status().isNoContent)
        getJson(u, "/api/v1/loans").andExpect(jsonPath("$[0].active").value(false))
        mvc.perform(post("/api/v1/loans/$id/reopen").with(jwt().jwt { it.subject(u.id.toString()) })).andExpect(status().isOk)

        send(other, "PUT", "/api/v1/loans/$id", body).andExpect(status().isNotFound)
        mvc.perform(delete("/api/v1/loans/$id").with(jwt().jwt { it.subject(other.id.toString()) })).andExpect(status().isNotFound)

        mvc.perform(delete("/api/v1/loans/$id").param("deletePayments", "false").with(jwt().jwt { it.subject(u.id.toString()) })).andExpect(status().isNoContent)
        getJson(u, "/api/v1/loans").andExpect(jsonPath("$.length()").value(0))
        getJson(u, "/api/v1/transactions?type=EXPENSE").andExpect(jsonPath("$.total").value(2))

        val id2 = tree(send(u, "POST", "/api/v1/loans", body))["id"].asLong()
        mvc.perform(delete("/api/v1/loans/$id2").param("deletePayments", "true").with(jwt().jwt { it.subject(u.id.toString()) })).andExpect(status().isNoContent)
        getJson(u, "/api/v1/transactions?type=EXPENSE").andExpect(jsonPath("$.total").value(2))
    }
}
