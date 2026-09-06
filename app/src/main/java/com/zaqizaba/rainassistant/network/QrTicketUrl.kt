package com.zaqizaba.rainassistant.network

import java.net.URI

object QrTicketUrl {
    fun resolve(
        topLevelTicket: String?,
        nestedTicket: String?,
        baseUrl: String,
    ): String? {
        val ticket = topLevelTicket.orEmpty().trim()
            .ifBlank { nestedTicket.orEmpty().trim() }
        if (ticket.isBlank()) return null

        return runCatching {
            val uri = URI(ticket)
            when {
                uri.isAbsolute &&
                    uri.scheme.lowercase() in setOf("http", "https") &&
                    !uri.host.isNullOrBlank() -> ticket

                !uri.isAbsolute && ticket.startsWith('/') && !ticket.startsWith("//") -> {
                    baseUrl.trimEnd('/') + ticket
                }

                else -> null
            }
        }.getOrNull()
    }
}
