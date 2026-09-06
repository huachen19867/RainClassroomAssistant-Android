package com.zaqizaba.rainassistant.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AnswerDelayTest {
    @Test
    fun clampsDelayToSupportedRange() {
        assertEquals(0, AnswerDelay.normalize(-1))
        assertEquals(3, AnswerDelay.normalize(3))
        assertEquals(60, AnswerDelay.normalize(120))
    }
}
