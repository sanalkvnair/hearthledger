package com.hearthledger.common

import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class VersionDto(val version: String, val commit: String)

@RestController
@RequestMapping("/api/v1/version")
class VersionController(
    @Value("\${APP_VERSION:dev}") private val version: String,
    @Value("\${GIT_SHA:}") private val commit: String,
) {
    @GetMapping
    fun get() = VersionDto(version.ifBlank { "dev" }, commit.take(7))
}
