package com.hearthledger

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.hearthledger.user.AppUser
import com.hearthledger.user.AppUserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
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
class Phase5IntegrationTest(@Autowired val mvc: MockMvc, @Autowired val users: AppUserRepository) {
    private val mapper = ObjectMapper()
    private val today = LocalDate.now()

    private fun user(n: String) = users.save(AppUser(email = "$n@p5.example.com", name = n, googleSub = "p5-$n-${System.nanoTime()}"))
    private fun send(u: AppUser, method: String, url: String, body: Map<String, Any?> = emptyMap()): ResultActions =
        mvc.perform(
            (if (method == "PUT") put(url) else post(url)).with(jwt().jwt { it.subject(u.id.toString()) })
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)),
        )
    private fun getJson(u: AppUser, url: String) = mvc.perform(get(url).with(jwt().jwt { it.subject(u.id.toString()) }))
    private fun tree(r: ResultActions): JsonNode = mapper.readTree(r.andReturn().response.contentAsString)

    private fun family(u: AppUser) = send(u, "POST", "/api/v1/families", mapOf("name" to "F", "baseCurrency" to "EUR")).andExpect(status().isCreated)
    private fun account(u: AppUser, name: String, type: String, extra: Map<String, Any?> = emptyMap()) =
        tree(send(u, "POST", "/api/v1/accounts", mapOf("name" to name, "type" to type, "currency" to "EUR") + extra))["id"].asLong()

    private fun expense(u: AppUser, amount: Int, date: LocalDate, account: Long) =
        send(u, "POST", "/api/v1/transactions", mapOf("type" to "EXPENSE", "amount" to amount, "currency" to "EUR", "date" to date.toString(), "accountId" to account))

    @Test
    fun `periods start on the previous business day when enabled`() {
        val u = user("period")
        family(u)
        send(
            u, "PUT", "/api/v1/family",
            mapOf("name" to "F", "cycleStartDay" to 25, "bufferMonths" to 3, "bufferLookbackMonths" to 3, "llmImportEnabled" to false, "adjustPeriodToBusinessDay" to true, "holidayCountry" to "SE"),
        ).andExpect(status().isOk)
        // 25 Oct 2026 is a Sunday
        getJson(u, "/api/v1/periods/current?date=2026-10-30")
            .andExpect(jsonPath("$.start").value("2026-10-23")).andExpect(jsonPath("$.end").value("2026-11-24"))
        send(
            u, "PUT", "/api/v1/family",
            mapOf("name" to "F", "cycleStartDay" to 25, "bufferMonths" to 3, "bufferLookbackMonths" to 3, "llmImportEnabled" to false, "holidayCountry" to "XX"),
        ).andExpect(status().isUnprocessableEntity)
        send(
            u, "PUT", "/api/v1/family",
            mapOf("name" to "F", "cycleStartDay" to 25, "bufferMonths" to 3, "bufferLookbackMonths" to 3, "llmImportEnabled" to false, "adjustPeriodToBusinessDay" to false),
        ).andExpect(status().isOk)
        getJson(u, "/api/v1/periods/current?date=2026-10-30").andExpect(jsonPath("$.start").value("2026-10-25"))
    }

    @Test
    fun `batch entry is all or nothing and default accounts are validated`() {
        val u = user("batch"); val other = user("batch-other")
        family(u); family(other)
        val bank = account(u, "Bank", "BANK")
        val foreign = account(other, "Theirs", "BANK")
        fun row(account: Long, amount: Int, category: Long? = null) =
            mapOf("type" to "EXPENSE", "amount" to amount, "currency" to "EUR", "date" to today.toString(), "accountId" to account, "categoryId" to category)

        send(u, "POST", "/api/v1/transactions/batch", mapOf("items" to listOf(row(bank, 10), row(bank, 20, 999999), row(foreign, 5))))
            .andExpect(status().isUnprocessableEntity).andExpect(jsonPath("$.code").value("BATCH_INVALID")).andExpect(jsonPath("$.errors.length()").value(2))
        getJson(u, "/api/v1/transactions").andExpect(jsonPath("$.total").value(0))

        send(u, "POST", "/api/v1/transactions/batch", mapOf("items" to listOf(row(bank, 10), row(bank, 20)))).andExpect(status().isCreated)
        getJson(u, "/api/v1/transactions").andExpect(jsonPath("$.total").value(2))

        send(u, "PUT", "/api/v1/me/default-accounts", mapOf("expenseAccountId" to bank)).andExpect(status().isOk)
        getJson(u, "/api/v1/me").andExpect(jsonPath("$.defaultExpenseAccountId").value(bank))
        send(u, "PUT", "/api/v1/me/default-accounts", mapOf("expenseAccountId" to foreign)).andExpect(status().isNotFound)
    }

    @Test
    fun `joint accounts count under each owner and investments are valued by profit or loss`() {
        val a = user("own-a"); val b = user("own-b")
        family(a)
        val code = tree(send(a, "POST", "/api/v1/family/invitations", mapOf("role" to "MEMBER")))["code"].asText()
        send(b, "POST", "/api/v1/invitations/$code/accept").andExpect(status().isOk)

        send(a, "POST", "/api/v1/accounts", mapOf("name" to "X", "type" to "SAVINGS", "currency" to "EUR", "ownership" to "PRIVATE", "ownerIds" to listOf(a.id, b.id)))
            .andExpect(status().isUnprocessableEntity)
        send(a, "POST", "/api/v1/accounts", mapOf("name" to "X", "type" to "SAVINGS", "currency" to "EUR", "ownership" to "JOINT", "ownerIds" to listOf(a.id)))
            .andExpect(status().isUnprocessableEntity)
        account(a, "Joint", "SAVINGS", mapOf("openingBalance" to 1000, "ownership" to "JOINT", "ownerIds" to listOf(a.id, b.id)))
        account(a, "Mine", "SAVINGS", mapOf("openingBalance" to 500, "ownership" to "PRIVATE", "ownerIds" to listOf(a.id)))
        val fund = account(a, "Fund", "INVESTMENT", mapOf("openingBalance" to 2000, "ownership" to "PRIVATE", "ownerIds" to listOf(a.id)))

        send(a, "POST", "/api/v1/savings/$fund/valuations", mapOf("valuedOn" to today.toString(), "profitLoss" to -250)).andExpect(status().isOk)
            .andExpect(jsonPath("$.marketValue").value(1750.0)).andExpect(jsonPath("$.profitLoss").value(-250.0))
        send(a, "POST", "/api/v1/savings/$fund/valuations", mapOf("valuedOn" to today.toString())).andExpect(status().isUnprocessableEntity)

        val overview = tree(getJson(a, "/api/v1/savings"))
        assertEquals(3250.0, overview["totalValue"].asDouble())
        assertEquals(1000.0, overview["sharedValue"].asDouble())
        assertEquals(2250.0, overview["individualValue"].asDouble())
        val members = overview["byMember"]
        assertEquals(3250.0, members.first { it["userId"].asLong() == a.id }["currentValue"].asDouble())
        assertEquals(1000.0, members.first { it["userId"].asLong() == b.id }["currentValue"].asDouble())

        val history = tree(getJson(a, "/api/v1/savings/history?accountId=$fund"))["points"]
        assertTrue(history.size() >= 1)
        assertEquals(1750.0, history.last()["value"].asDouble())
        assertEquals(2000.0, history.last()["invested"].asDouble())
    }

    @Test
    fun `credit card bill is not double counted and shows what is still due`() {
        val u = user("card")
        family(u)
        val bank = account(u, "Bank", "BANK", mapOf("openingBalance" to 5000))
        val card = account(u, "Visa", "CREDIT_CARD", mapOf("statementDay" to 14, "dueDay" to 28))
        send(u, "POST", "/api/v1/accounts", mapOf("name" to "B2", "type" to "BANK", "currency" to "EUR", "statementDay" to 3)).andExpect(status().isUnprocessableEntity)

        val closed = if (today.dayOfMonth >= 14) today.withDayOfMonth(14) else today.minusMonths(1).withDayOfMonth(14)
        expense(u, 100, closed, card).andExpect(status().isCreated)
        expense(u, 40, closed.minusMonths(1), card).andExpect(status().isCreated)

        val summary = tree(getJson(u, "/api/v1/accounts/$card/card-summary"))
        assertEquals(closed.toString(), summary["statementClosedOn"].asText())
        assertEquals(100.0, summary["statementAmount"].asDouble())
        assertEquals(100.0, summary["remaining"].asDouble())
        assertEquals(if (closed.plusMonths(0).withDayOfMonth(28).isAfter(closed)) closed.withDayOfMonth(28).toString() else "", summary["dueDate"].asText())

        if (today.isAfter(closed)) {
            send(
                u, "POST", "/api/v1/transactions",
                mapOf("type" to "TRANSFER", "amount" to 60, "currency" to "EUR", "date" to today.toString(), "accountId" to bank, "toAccountId" to card),
            ).andExpect(status().isCreated)
            getJson(u, "/api/v1/accounts/$card/card-summary").andExpect(jsonPath("$.remaining").value(40.0))
            getJson(u, "/api/v1/dashboard/card-bills").andExpect(jsonPath("$[0].remaining").value(40.0))
        }
        // purchases are the expense; paying the bill is a transfer and never an expense or saving
        val dash = tree(getJson(u, "/api/v1/dashboard?date=$closed&paymentMethod=CARD"))
        assertEquals(0.0, dash["savings"].asDouble())
        assertTrue(dash["expenseByCategory"].size() >= 1)
        assertEquals(0, tree(getJson(u, "/api/v1/dashboard?date=$closed&paymentMethod=CASH"))["expenseByCategory"].size())
    }

    @Test
    fun `fixed amortization, interest only and balloon loans book their own payments`() {
        val u = user("loans")
        family(u)
        val start = today.minusMonths(1)

        val fixed = tree(
            send(
                u, "POST", "/api/v1/loans",
                mapOf("name" to "Home A", "currency" to "EUR", "principal" to 120000, "interestRate" to 2.75, "loanType" to "FIXED_AMORTIZATION", "amortizationRate" to 2, "startDate" to start.toString()),
            ).andExpect(status().isCreated),
        )
        assertEquals(2, fixed["paidInstalments"].asInt())
        assertEquals(600, fixed["tenureMonths"].asInt())
        assertTrue(fixed["remainingBalance"].asDouble() < 120000.0)

        val interestOnly = tree(
            send(
                u, "POST", "/api/v1/loans",
                mapOf("name" to "Home B", "currency" to "EUR", "principal" to 100000, "interestRate" to 2.75, "loanType" to "INTEREST_ONLY", "startDate" to start.toString()),
            ).andExpect(status().isCreated),
        )
        assertNull(interestOnly["tenureMonths"].takeUnless { it.isNull })
        assertEquals(100000.0, interestOnly["remainingBalance"].asDouble())
        assertEquals(229.17, interestOnly["nextPayment"].asDouble(), 0.01)

        val car = tree(
            send(
                u, "POST", "/api/v1/loans",
                mapOf(
                    "name" to "Car", "currency" to "EUR", "principal" to 30000, "downPayment" to 6000, "balloonAmount" to 12000, "interestRate" to 5,
                    "tenureMonths" to 36, "setupFee" to 600, "monthlyFee" to 35, "loanType" to "BALLOON_ANNUITY", "startDate" to start.toString(),
                ),
            ).andExpect(status().isCreated),
        )
        assertEquals(2, car["paidInstalments"].asInt())
        assertEquals(34, car["remainingInstalments"].asInt())
        assertNotNull(car["effectiveRate"].takeUnless { it.isNull })
        assertTrue(car["effectiveRate"].asDouble() > 5.1)

        send(u, "POST", "/api/v1/loans", mapOf("name" to "Bad", "currency" to "EUR", "principal" to 1000, "loanType" to "FIXED_AMORTIZATION", "startDate" to start.toString()))
            .andExpect(status().isUnprocessableEntity)
        send(u, "POST", "/api/v1/loans/preview", mapOf("name" to "P", "currency" to "EUR", "principal" to 30000, "downPayment" to 6000, "balloonAmount" to 12000, "interestRate" to 5, "tenureMonths" to 36, "loanType" to "BALLOON_ANNUITY", "startDate" to today.toString()))
            .andExpect(status().isOk).andExpect(jsonPath("$.financed").value(24000.0)).andExpect(jsonPath("$.payments").value(36))

        // each booked payment carries its interest/principal split; the loan payments count as expenses
        getJson(u, "/api/v1/transactions?type=EXPENSE").andExpect(jsonPath("$.total").value(6))

        // a new rate applies to the remaining payments only
        send(u, "POST", "/api/v1/loans/${fixed["id"].asLong()}/rates", mapOf("effectiveFrom" to today.plusMonths(2).toString(), "annualRate" to 4.0))
            .andExpect(status().isCreated).andExpect(jsonPath("$.rates.length()").value(1))
        // re-running generation never duplicates
        getJson(u, "/api/v1/transactions?type=EXPENSE").andExpect(jsonPath("$.total").value(6))
    }
}
