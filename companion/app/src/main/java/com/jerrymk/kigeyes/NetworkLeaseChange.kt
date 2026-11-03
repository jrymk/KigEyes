package com.jerrymk.kigeyes

/** An address can be reused on a different Android Network after reconnect. */
internal object NetworkLeaseChange {
    fun changed(oldHandle: Long?, oldAddress: String?, handle: Long, address: String): Boolean =
        oldHandle != handle || oldAddress != address
}
