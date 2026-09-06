package com.zaqizaba.rainassistant.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkPhaseTest {
    @Test
    fun readinessMatchesVisibleStateContract() {
        assertFalse(WorkPhase.NEED_LOGIN.canAnswer)
        assertFalse(WorkPhase.NEED_API.canAnswer)
        assertFalse(WorkPhase.VALIDATING_LOGIN.canAnswer)
        assertFalse(WorkPhase.NETWORK_ERROR.canAnswer)
        assertTrue(WorkPhase.READY.canAnswer)
        assertTrue(WorkPhase.MONITORING.canAnswer)
        assertTrue(WorkPhase.WAITING_TO_SOLVE.canAnswer)
        assertTrue(WorkPhase.SOLVING.canAnswer)
    }
}
