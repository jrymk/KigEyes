package com.jerrymk.kigeyes
import org.junit.Assert.*
import org.junit.Test

class RecordingModeTest {
    @Test fun oldPreferencesKeepOriginalQuality() {
        assertEquals(RecordingMode.HIGH,RecordingMode.parse(null))
        assertEquals(RecordingMode.HIGH,RecordingMode.parse("unknown"))
        assertEquals(16_000_000,RecordingMode.HIGH.bitrate(CaptureQuality.FULL_HD))
    }
    @Test fun storageChoicesHaveIndependentCaptureAndPlaybackRates() {
        assertEquals(60,RecordingMode.BALANCED.mbPerMinute(CaptureQuality.FULL_HD).toInt())
        assertEquals(12,RecordingMode.LOW_FPS.mbPerMinute(CaptureQuality.FULL_HD).toInt())
        assertEquals(30,RecordingMode.TIMELAPSE.fps*RecordingMode.TIMELAPSE.speed)
        for(mode in RecordingMode.entries) {
            assertTrue(mode.bitrate(CaptureQuality.UHD)>0)
            assertEquals(mode,RecordingMode.parse(mode.name))
        }
    }
    @Test fun realTimeKeepsCaptureGapsAndTimeLapseCompressesThem() {
        val normal=RecordingTimeline()
        val lapse=RecordingTimeline(15)
        for((i,t) in listOf(5_000_000L,5_500_000L,8_000_000L).withIndex()) {
            val delta=t-5_000_000L
            assertEquals(delta,normal.timestamp(t))
            assertEquals(delta/15,lapse.timestamp(t))
        }
    }
    @Test fun invalidOrRepeatedTimestampsAreRejected() {
        val clock=RecordingTimeline(15)
        clock.timestamp(1_000_000)
        assertThrows(IllegalArgumentException::class.java) { clock.timestamp(999_999) }
        assertThrows(IllegalArgumentException::class.java) { clock.timestamp(1_000_000) }
    }
    @Test fun unsupportedProcessingKeepsTemplateDefault() {
        assertNull(CameraProcessing.choose(null,2))
        assertNull(CameraProcessing.choose(intArrayOf(0,1),2))
        assertEquals(2,CameraProcessing.choose(intArrayOf(0,1,2),2))
    }
}
