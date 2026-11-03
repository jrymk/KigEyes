package com.jerrymk.kigeyes

import android.content.Context

/** Pairing and Wi-Fi share a task sheet; success unlocks normal dismiss gestures. */
internal class ConnectionDialog(context: Context,onConnect: ()->Unit,onCancel: ()->Unit,onSettings: ()->Unit,onClosed: ()->Unit = {}) {
    private var complete=false
    private val sheet: SetupSheet=SetupSheet(context,{ if(complete) { onClosed();dismiss() } else onConnect() },{ if(!complete)onCancel();onClosed() },onSettings)
    val isShowing get()=sheet.isShowing
    fun show()=sheet.show()
    fun dismiss()=sheet.dismiss()
    fun render(busy: Boolean,detail: String,failed: Boolean=false,complete: Boolean=false) {
        this.complete=complete
        sheet.render(if(complete)"Connected" else if(busy)"Connecting to KigEyes" else "Connect to KigEyes",detail,
            if(complete)"Done" else if(failed)"Try again" else "Connect",busy,!busy,completed=complete)
    }
}
