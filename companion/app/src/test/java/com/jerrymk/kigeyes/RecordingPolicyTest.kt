package com.jerrymk.kigeyes

import org.junit.Assert.*
import org.junit.Test

class RecordingPolicyTest {
    private val plenty=95L*1024*1024*1024
    @Test fun rejectsCriticalHeatBeforeStartingEvenWithFreeStorage() {
        for(status in 4..6) {
            assertTrue(RecordingPolicy.tooHot(status))
            assertTrue(RecordingPolicy.startError(true,false,plenty,status)!!.startsWith("Phone too hot"))
        }
    }
    @Test fun recoversAfterCoolingWithoutBlockingOrdinaryThrottling() {
        for(status in 0..3) {
            assertFalse(RecordingPolicy.tooHot(status))
            assertNull(RecordingPolicy.startError(true,false,plenty,status))
        }
    }
    @Test fun retainsStorageAndCameraGuards() {
        assertEquals("Camera not ready",RecordingPolicy.startError(false,false,plenty,0))
        assertEquals("Still saving the previous recording",RecordingPolicy.startError(true,true,plenty,0))
        assertEquals("Less than 512 MB storage free",RecordingPolicy.startError(true,false,0,0))
    }
}
