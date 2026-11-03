package com.jerrymk.kigeyes

/** Storage policy for local video only. Camera/RTSP cadence stays at 30 fps. */
enum class RecordingMode(val title: String,val fps: Int,val speed: Int,val scale: Double) {
    HIGH("High detail",30,1,1.0),
    BALANCED("Balanced",30,1,.5),
    SMALL("Small file",30,1,.25),
    LOW_FPS("Low fps · real time",5,1,.10),
    TIMELAPSE("Time-lapse · 15×",2,15,.06);

    fun bitrate(q: CaptureQuality)=(q.bitrate*scale).toInt()
    fun mbPerMinute(q: CaptureQuality)=bitrate(q)*60L/8/1_000_000
    fun description(q: CaptureQuality): String = when(this) {
        LOW_FPS -> "5 fps · normal duration"
        TIMELAPSE -> "2 frames/s → 30 fps playback"
        else -> "30 fps"
    }+" · ~${mbPerMinute(q)} MB/min recorded"
    companion object {
        // Preserve existing recordings' quality until the user changes it.
        fun parse(value: String?)=entries.firstOrNull { it.name==value } ?: HIGH
    }
}

/** Preserve real capture gaps, including interruptions, at the selected speed. */
class RecordingTimeline(private val speed: Int=1) {
    private var origin: Long?=null
    private var previous=-1L
    init { require(speed>=1) }
    fun timestamp(sourceUs: Long): Long {
        val first=origin ?: sourceUs.also { origin=it }
        val value=(sourceUs-first)/speed
        require(value>=0 && value>previous) { "Non-monotonic camera timestamp" }
        previous=value
        return value
    }
}
