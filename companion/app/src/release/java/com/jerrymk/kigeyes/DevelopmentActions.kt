package com.jerrymk.kigeyes

/** Release builds have no developer command ingress. */
internal object DevelopmentActions {
    fun take(): String? = null
}
