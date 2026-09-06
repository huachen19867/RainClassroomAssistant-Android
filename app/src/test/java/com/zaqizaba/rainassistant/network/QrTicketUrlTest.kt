package com.zaqizaba.rainassistant.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrTicketUrlTest {
    private val baseUrl = "https://www.yuketang.cn"

    @Test
    fun acceptsAbsoluteAndNestedServerRelativeTickets() {
        assertEquals(
            "https://cdn.example.test/qr.png",
            QrTicketUrl.resolve("https://cdn.example.test/qr.png", null, baseUrl),
        )
        assertEquals(
            "https://www.yuketang.cn/login/qr.png",
            QrTicketUrl.resolve(null, "/login/qr.png", baseUrl),
        )
    }

    @Test
    fun rejectsEmptyAndUnsafeTickets() {
        assertNull(QrTicketUrl.resolve(null, null, baseUrl))
        assertNull(QrTicketUrl.resolve("javascript:alert(1)", null, baseUrl))
        assertNull(QrTicketUrl.resolve("qr.png", null, baseUrl))
        assertNull(QrTicketUrl.resolve("//other.example.test/qr.png", null, baseUrl))
    }
}
