package com.jerrymk.kigeyes

import android.content.Context
import android.view.OrientationEventListener

/** Physical orientation, independent of Android's screen rotation lock. */
class PhonePose(context: Context): OrientationEventListener(context) {
    @Volatile var deviceDegrees=270;private set
    private var candidate=-1
    private var since=0L
    override fun onOrientationChanged(degrees: Int) {
        if(degrees==ORIENTATION_UNKNOWN)return
        val next=((degrees+45)/90*90)%360
        val distance=kotlin.math.abs((degrees-next+540)%360-180)
        if(distance>32)return // dead band near the quadrant boundary
        val now=android.os.SystemClock.elapsedRealtime()
        if(next!=candidate) { candidate=next;since=now }
        if(now-since>=250)deviceDegrees=next
    }
    fun imageRotation(sensor: Int)=(sensor+deviceDegrees)%360
}
