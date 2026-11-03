package com.jerrymk.kigeyes

import org.junit.Assert.*
import org.junit.Test

class MirrorWorkerStateTest {
    @Test fun companionRouteShownOnlyForLiveStream() {
        val live=MirrorWorkerState(true,true,true,true,networkRoute="companion_wifi")
        assertTrue(live.detail.contains("app’s KigEyes Wi-Fi"))
        assertFalse(live.copy(streaming=false,connected=false).detail.contains("Sharing over"))
        assertFalse(live.copy(networkRoute="default").detail.contains("app’s KigEyes Wi-Fi"))
    }
    @Test fun disconnectedWorkerIsNotMisreportedAsMissing() {
        val state=MirrorWorkerState(available=true,allowed=true)
        assertEquals("Waiting for KigEyes",state.title)
        assertTrue(state.detail.contains("no ADB"));assertFalse(state.promotable)
    }
    @Test fun localPauseWinsOverStaleStreamState() {
        val state=MirrorWorkerState(true,false,true,true)
        assertEquals("Mirroring paused",state.title);assertFalse(state.promotable)
    }
    @Test fun onlyLiveStreamRequestsPromotion() {
        assertTrue(MirrorWorkerState(true,true,true,true).promotable)
        assertFalse(MirrorWorkerState(true,true,true,false).promotable)
        assertFalse(MirrorWorkerState(stopping=true).promotable)
    }
    @Test fun missingWorkerExplainsAuthorizedUsbSetup() {
        assertTrue(MirrorWorkerState().detail.contains("USB"))
        assertTrue(MirrorWorkerState().detail.contains("approve debugging"))
        assertEquals("Worker unavailable",MirrorWorkerState().title)
    }
}
