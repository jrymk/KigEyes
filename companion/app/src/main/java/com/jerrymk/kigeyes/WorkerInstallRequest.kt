package com.jerrymk.kigeyes

import android.os.SystemClock
import java.security.SecureRandom

/** Short-lived user intent, readable only by authorized USB shell through WorkerProvider. */
internal object WorkerInstallRequest {
    private var nonce=""
    private var deadline=0L
    @Volatile var message="";private set
    @Synchronized fun begin(): String {
        nonce=ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        message=""
        deadline=SystemClock.elapsedRealtime()+300_000
        return nonce
    }
    @Synchronized fun current(): String=if(SystemClock.elapsedRealtime()<deadline)nonce else ""
    @Synchronized fun report(request: String?,stage: String?) {
        require(request!=null && current().isNotEmpty() && request==current()) { "Setup request expired" }
        message=when(stage) {
            "installing"->"KigEyes found this phone. Installing the worker…"
            "ready"->"Installed. Waiting for the worker to start…"
            "error"->"Could not install. Check USB debugging, then close setup and try again."
            else->throw IllegalArgumentException("Unknown installation stage")
        }
    }
    @Synchronized fun clear() { nonce="";deadline=0 }
}
