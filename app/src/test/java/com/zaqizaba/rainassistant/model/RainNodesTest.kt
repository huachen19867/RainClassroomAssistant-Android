package com.zaqizaba.rainassistant.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RainNodesTest {
    @Test
    fun containsOriginalFourNodesAndFallsBackToMainSite() {
        assertEquals(listOf("www", "changjiang", "huanghe", "pro"), RainNodes.all.map { it.key })
        assertEquals("www.yuketang.cn", RainNodes.fromKey("unknown").host)
        assertTrue(RainNodes.fromKey("huanghe").webSocketUrl.startsWith("wss://huanghe.yuketang.cn/"))
    }
}
