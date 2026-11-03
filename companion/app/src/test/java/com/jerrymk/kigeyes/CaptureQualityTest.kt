package com.jerrymk.kigeyes
import org.junit.Assert.*
import org.junit.Test
class CaptureQualityTest {
    @Test fun streamingProfilesCannotBecomeLocalRecordingQuality() {
        for(q in listOf(CaptureQuality.STREAM_540,CaptureQuality.HD)) {
            assertTrue(q.isStream)
            assertEquals(CaptureQuality.FULL_HD,CaptureQuality.recording(q.name))
        }
    }
    @Test fun originalRecordingProfilesRemainAvailable() {
        for(q in listOf(CaptureQuality.FULL_HD,CaptureQuality.UHD)) {
            assertFalse(q.isStream)
            assertEquals(q,CaptureQuality.recording(q.name))
        }
        assertEquals(CaptureQuality.FULL_HD,CaptureQuality.recording(null))
    }
}
