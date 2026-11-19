package com.openfamilybudget

import com.fasterxml.jackson.databind.ObjectMapper
import com.openfamilybudget.ratelimit.WindowCounter
import com.openfamilybudget.user.AppUser
import com.openfamilybudget.user.AppUserRepository
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class Phase4Test(@Autowired val mvc: MockMvc, @Autowired val users: AppUserRepository) {
    private val mapper = ObjectMapper()

    @Test
    fun `window counter blocks over the limit and resets next minute`() {
        var now = 0L
        val c = WindowCounter { now }
        assertTrue(c.allow("a", 2)); assertTrue(c.allow("a", 2)); assertFalse(c.allow("a", 2))
        assertTrue(c.allow("b", 2))
        now = 61_000
        assertTrue(c.allow("a", 2))
    }

    @Test
    fun `mutations are audited and only admins can read the log`() {
        val owner = users.save(AppUser(email = "aud@p4.example.com", name = "Owner", googleSub = "p4-${System.nanoTime()}"))
        val auth = jwt().jwt { it.subject(owner.id.toString()) }
        mvc.perform(post("/api/v1/families").with(auth).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(mapOf("name" to "F", "baseCurrency" to "EUR")))).andExpect(status().isCreated)
        mvc.perform(post("/api/v1/accounts").with(auth).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(mapOf("name" to "Bank", "type" to "BANK", "currency" to "EUR")))).andExpect(status().isCreated)
        mvc.perform(get("/api/v1/audit").with(auth)).andExpect(status().isOk)
            .andExpect(jsonPath("$[0].entity").value("accounts")).andExpect(jsonPath("$[0].userName").value("Owner"))
    }
}
