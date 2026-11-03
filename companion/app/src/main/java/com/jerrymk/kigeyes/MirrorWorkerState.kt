package com.jerrymk.kigeyes

/** Presentation policy shared by the sheet and notification; no network assumptions. */
data class MirrorWorkerState(
    val available: Boolean=false, val allowed: Boolean=false, val connected: Boolean=false,
    val streaming: Boolean=false, val version: String="", val stopping: Boolean=false,
    val checking: Boolean=false, val error: String="", val networkRoute: String="",
    val screenOffSupported: Boolean=false, val screenGuard: Int=0
) {
    val title: String get()=when {
        checking->"Checking worker…"
        stopping->"Worker stopped"
        !available->"Worker unavailable"
        !allowed->"Mirroring paused"
        screenGuard==1->"Screen off · Power locks"
        streaming->"Streaming to KigEyes"
        connected->"Ready · standby"
        else->"Waiting for KigEyes"
    }
    val detail: String get()=when {
        error.isNotEmpty()->error
        stopping || (!available && !checking)->"Open Screen sharing setup. Connect USB to KigEyes and approve debugging."
        checking->"Checking the worker on this phone."
        !allowed->"Paused on this phone. The worker stays ready; no ADB needed to resume."
        screenGuard==1->"Press Power to lock Android and restore the screen."
        streaming->if(networkRoute=="companion_wifi") "Sharing over the app’s KigEyes Wi-Fi connection." else "Your phone screen is being shared."
        connected->"Worker running. Enable Phone screen on KigEyes to start mirroring."
        else->"Worker running. Reconnect to KigEyes Wi-Fi; no ADB needed."
    }
    val promotable: Boolean get()=available && allowed && streaming && !stopping
}
