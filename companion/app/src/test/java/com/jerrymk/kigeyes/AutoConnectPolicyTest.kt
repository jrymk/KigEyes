package com.jerrymk.kigeyes

import org.junit.Assert.*
import org.junit.Test

class AutoConnectPolicyTest {
    private fun AutoConnectPolicy.scan(enabled: Boolean=true,owned: Boolean=true,concurrent: Boolean=true,
        trusted: Boolean=true,phase: WifiPhase=WifiPhase.IDLE,provisioning: Boolean=false)=
        shouldScan(enabled,owned,concurrent,trusted,phase,provisioning)
    private fun AutoConnectPolicy.found()=nearby(true,true,true,true,WifiPhase.IDLE,false)

    @Test fun requiresOwnerCapabilityIdentityAndOptIn() {
        val p=AutoConnectPolicy()
        assertTrue(p.scan())
        assertFalse(p.scan(enabled=false));assertFalse(p.scan(owned=false))
        assertFalse(p.scan(concurrent=false));assertFalse(p.scan(trusted=false))
        assertFalse(p.scan(phase=WifiPhase.CONNECTING));assertFalse(p.scan(phase=WifiPhase.CONNECTED))
        assertFalse(p.scan(phase=WifiPhase.FAILED));assertFalse(p.scan(provisioning=true))
    }
    @Test fun advertisementBurstMakesOnlyOneRequest() {
        val p=AutoConnectPolicy();assertTrue(p.found())
        repeat(100) { assertFalse(p.found());assertFalse(p.scan()) }
    }
    @Test fun rejectionDoesNotPromptAgainUntilExplicitRetry() {
        val p=AutoConnectPolicy();assertTrue(p.found());p.observe(WifiPhase.FAILED)
        p.observe(WifiPhase.IDLE);assertFalse(p.found())
        p.resume();assertTrue(p.found())
    }
    @Test fun disconnectOrCancelSuppressesPresence() {
        val p=AutoConnectPolicy();p.suspend()
        p.observe(WifiPhase.CONNECTED);p.observe(WifiPhase.IDLE)
        assertFalse(p.scan());assertFalse(p.found())
        p.resume();assertTrue(p.scan())
    }
    @Test fun successfulConnectionCanReconnectAfterLoss() {
        val p=AutoConnectPolicy();assertTrue(p.found())
        p.observe(WifiPhase.CONNECTED);assertFalse(p.scan(phase=WifiPhase.CONNECTED))
        p.observe(WifiPhase.IDLE);assertTrue(p.scan());assertTrue(p.found())
    }
    @Test fun enablingDoesNotOverrideCurrentNetworkOrProvisioning() {
        val p=AutoConnectPolicy();p.suspend();p.resume()
        assertFalse(p.nearby(true,true,true,true,WifiPhase.CONNECTED,false))
        assertFalse(p.nearby(true,true,true,true,WifiPhase.IDLE,true))
        assertTrue(p.found())
    }
}
