package com.jerrymk.kigeyes

import org.junit.Assert.*
import org.junit.Test

class HomeSharingStateTest {
    @Test fun neverPromisesToStartMissingPrivilegedWorker() {
        val state=HomeSharingState.from(MirrorWorkerState())
        assertEquals(HomeShareAction.SETUP,state.action)
        assertTrue(state.detail.contains("USB debugging"))
        assertFalse(state.title.contains("not installed"))
    }
    @Test fun pauseWinsOverStaleStreamTelemetry() {
        val state=HomeSharingState.from(MirrorWorkerState(true,false,true,true))
        assertEquals(HomeShareAction.ALLOW,state.action)
        assertEquals("Sharing paused",state.title)
    }
    @Test fun PermissionDoesNotClaimLiveSharing() {
        val state=HomeSharingState.from(MirrorWorkerState(true,true,true,false))
        assertEquals("Ready to share",state.title)
        assertTrue(state.detail.contains("glasses"))
    }
    @Test fun disconnectedWorkerOffersConnectionNotReinstallation() {
        val state=HomeSharingState.from(MirrorWorkerState(true,true,false,false))
        assertEquals("Waiting for KigEyes",state.title)
        assertNotEquals(HomeShareAction.SETUP,state.action)
    }
    @Test fun pendingCommandCannotBeSubmittedTwice() {
        assertFalse(HomeSharingState.from(MirrorWorkerState(true,true,true,true),true).enabled)
        assertFalse(HomeSharingState.from(MirrorWorkerState(checking=true)).enabled)
    }
}
