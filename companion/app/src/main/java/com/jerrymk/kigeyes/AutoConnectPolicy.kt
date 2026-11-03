package com.jerrymk.kigeyes

enum class WifiPhase { IDLE, CONNECTING, CONNECTED, FAILED }

/** One owner decides when to request Wi-Fi. Rejections require an explicit retry. */
internal class AutoConnectPolicy {
    var suspended=false; private set
    private var attempted=false

    fun suspend() { suspended=true }
    fun resume() { suspended=false;attempted=false }
    fun observe(phase: WifiPhase) {
        if(phase==WifiPhase.CONNECTED)attempted=false
        if(phase==WifiPhase.FAILED)suspended=true
    }
    fun shouldScan(enabled: Boolean,owned: Boolean,concurrent: Boolean,trusted: Boolean,
                   phase: WifiPhase,provisioning: Boolean): Boolean =
        enabled && owned && concurrent && trusted && !provisioning && !suspended &&
            !attempted && phase==WifiPhase.IDLE

    fun nearby(enabled: Boolean,owned: Boolean,concurrent: Boolean,trusted: Boolean,
               phase: WifiPhase,provisioning: Boolean): Boolean {
        if(!shouldScan(enabled,owned,concurrent,trusted,phase,provisioning))return false
        attempted=true
        return true
    }
}
