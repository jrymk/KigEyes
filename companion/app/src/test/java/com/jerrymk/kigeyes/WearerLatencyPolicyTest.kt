package com.jerrymk.kigeyes

import org.junit.Assert.*
import org.junit.Test

class WearerLatencyPolicyTest {
    @Test fun catchesUpWithoutSeekingAndReturnsToNormal() {
        var backlog=2000.0
        repeat(80) { backlog -= (WearerLatencyPolicy.speed(backlog.toLong(),true)-1f)*250 }
        assertTrue("Backlog must not remain seconds behind",backlog<=180)
        assertEquals(1f,WearerLatencyPolicy.speed(backlog.toLong(),true),0f)
    }
    @Test fun doesNotSpeedUpStarvedOrPausedPlayback() {
        for (buffer in listOf(-1L,0L,80L,180L))
            assertEquals(1f,WearerLatencyPolicy.speed(buffer,true),0f)
        assertEquals(1f,WearerLatencyPolicy.speed(5000,false),0f)
    }
    @Test fun rebufferHasSmallStartupBudget() {
        assertTrue(WearerLatencyPolicy.START_BUFFER_MS<=WearerLatencyPolicy.MIN_BUFFER_MS)
        assertTrue(WearerLatencyPolicy.REBUFFER_MS<=WearerLatencyPolicy.MIN_BUFFER_MS)
        assertTrue(WearerLatencyPolicy.MIN_BUFFER_MS<=WearerLatencyPolicy.MAX_BUFFER_MS)
    }
}
