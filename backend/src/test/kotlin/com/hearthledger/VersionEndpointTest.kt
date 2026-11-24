package com.hearthledger

import com.hearthledger.user.AppUser
import com.hearthledger.user.AppUserRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class VersionEndpointTest(@Autowired val mvc: MockMvc, @Autowired val users: AppUserRepository) {
    @Test
    fun `version is returned to signed-in users only`() {
        val u = users.save(AppUser(email = "v@ver.example.com", name = "v", googleSub = "ver-${System.nanoTime()}"))
        mvc.perform(get("/api/v1/version")).andExpect(status().isUnauthorized)
        mvc.perform(get("/api/v1/version").with(jwt().jwt { it.subject(u.id.toString()) }))
            .andExpect(status().isOk).andExpect(jsonPath("$.version").value("dev"))
    }
}
