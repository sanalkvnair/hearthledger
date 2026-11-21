package com.hearthledger

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.hearthledger.user.AppUser
import com.hearthledger.user.AppUserRepository
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class Phase3IntegrationTest(@Autowired val mvc: MockMvc, @Autowired val users: AppUserRepository) {
    private val mapper = ObjectMapper()

    private fun user(n: String) = users.save(AppUser(email = "$n@p3.example.com", name = n, googleSub = "p3-$n-${System.nanoTime()}"))
    private fun auth(u: AppUser) = jwt().jwt { it.subject(u.id.toString()) }
    private fun json(u: AppUser, url: String, body: Any) =
        mvc.perform(post(url).with(auth(u)).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
    private fun tree(r: org.springframework.test.web.servlet.ResultActions): JsonNode = mapper.readTree(r.andReturn().response.contentAsString)

    @Test
    fun `csv import categorises, flags duplicates, commits and exports`() {
        val u = user("p3")
        json(u, "/api/v1/families", mapOf("name" to "F", "baseCurrency" to "EUR")).andExpect(status().isCreated)
        val bank = tree(json(u, "/api/v1/accounts", mapOf("name" to "Bank", "type" to "BANK", "currency" to "EUR")))["id"].asLong()
        val groceries = tree(mvc.perform(get("/api/v1/categories").with(auth(u)))).first { it["name"].asText() == "Groceries" }["id"].asLong()
        json(u, "/api/v1/import/rules", mapOf("keyword" to "supermarket", "categoryId" to groceries)).andExpect(status().isCreated)

        val csv = "Date;Text;Amount\n02/01/2026;SUPERMARKET Central;-45,10\n03/01/2026;Salary;2500,00\n"
        val file = MockMultipartFile("file", "s.csv", "text/csv", csv.toByteArray())
        val mapping = mapper.writeValueAsString(
            mapOf("dateColumn" to "Date", "dateFormat" to "dd/MM/yyyy", "descriptionColumn" to "Text", "amountColumn" to "Amount", "decimalSeparator" to ","),
        )
        fun preview() = tree(mvc.perform(multipart("/api/v1/import/csv/preview").file(file).param("accountId", "$bank").param("mapping", mapping).with(auth(u))).andExpect(status().isOk))

        val first = preview()
        assertTrue(first["rows"][0]["categoryId"].asLong() == groceries)
        assertTrue(first["rows"][0]["type"].asText() == "EXPENSE" && first["rows"][1]["type"].asText() == "INCOME")
        assertTrue(!first["rows"][0]["duplicate"].asBoolean())

        json(u, "/api/v1/import/commit", mapOf("accountId" to bank, "rows" to first["rows"])).andExpect(status().isOk).andExpect(jsonPath("$.imported").value(2))
        assertTrue(preview()["rows"].all { it["duplicate"].asBoolean() })

        val export = mvc.perform(get("/api/v1/export/transactions.csv").with(auth(u))).andExpect(status().isOk).andReturn().response.contentAsString
        assertTrue(export.contains("SUPERMARKET Central") && export.contains("Groceries"))
        mvc.perform(get("/api/v1/export/transactions.xlsx").with(auth(u))).andExpect(status().isOk)

        // PDF import is blocked until the family opts in
        val pdf = MockMultipartFile("file", "s.pdf", "application/pdf", "x".toByteArray())
        mvc.perform(multipart("/api/v1/import/pdf/preview").file(pdf).param("accountId", "$bank").with(auth(u))).andExpect(status().isUnprocessableEntity)
    }
}
