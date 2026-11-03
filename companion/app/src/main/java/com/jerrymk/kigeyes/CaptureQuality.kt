package com.jerrymk.kigeyes

enum class CaptureQuality(val label: String,val width: Int,val height: Int,val bitrate: Int) {
    STREAM_540("540p30",960,540,6_000_000),
    HD("720p30",1280,720,6_000_000),
    FULL_HD("1080p30",1920,1080,16_000_000),
    UHD("4K30",3840,2160,40_000_000);
    val isStream get()=this==STREAM_540 || this==HD
    companion object {
        fun recording(value: String?)=listOf(FULL_HD,UHD).firstOrNull { it.name==value } ?: FULL_HD
    }
}
