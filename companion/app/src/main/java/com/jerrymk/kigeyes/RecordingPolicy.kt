package com.jerrymk.kigeyes

import android.os.PowerManager

/** One start/stop policy for local buttons, remote commands and thermal checks. */
object RecordingPolicy {
    fun tooHot(status: Int)=status>=PowerManager.THERMAL_STATUS_CRITICAL

    fun startError(ready: Boolean,saving: Boolean,freeBytes: Long,thermalStatus: Int): String? = when {
        !ready -> "Camera not ready"
        saving -> "Still saving the previous recording"
        tooHot(thermalStatus) -> "Phone too hot · let it cool before recording"
        freeBytes<512L*1024*1024 -> "Less than 512 MB storage free"
        else -> null
    }
}
