package com.jerrymk.kigeyes

import org.junit.Assert.*
import org.junit.Test

class NetworkLeaseChangeTest {
    @Test fun reusedAddressOnNewNetworkRebindsConsumers() {
        assertTrue(NetworkLeaseChange.changed(100, "10.42.0.2", 101, "10.42.0.2"))
    }
    @Test fun duplicateCallbackDoesNotRestartConsumers() {
        assertFalse(NetworkLeaseChange.changed(100, "10.42.0.2", 100, "10.42.0.2"))
    }
    @Test fun addressChangesAndFirstConnectionNotify() {
        assertTrue(NetworkLeaseChange.changed(100, "10.42.0.2", 100, "10.42.0.3"))
        assertTrue(NetworkLeaseChange.changed(null, null, 100, "10.42.0.2"))
    }
}
