package com.jerrymk.kigeyes

import org.junit.Assert.*
import org.junit.Test

class CameraZoomTest {
    @Test fun remoteZoomUsesCameraLimitsAndRejectsMalformedValues() {
        val zoom=CameraZoom(.6f,4f)
        assertEquals(.6f,zoom.remote(.5),0f)
        assertEquals(4f,zoom.remote(5.0),0f)
        assertEquals(2.25f,zoom.remote(2.25),0f)
        for(value in listOf(Double.NaN,Double.POSITIVE_INFINITY,0.0,-1.0,101.0)) {
            assertThrows(IllegalArgumentException::class.java) { zoom.remote(value) }
        }
    }
    @Test fun pixelPresetsAndLimits() {
        val zoom=CameraZoom(.49447829f,30f)
        assertEquals(listOf(.5f,1f,2f,5f),zoom.presets)
        assertEquals(30f,zoom.pinch(20f,2f),0f)
        assertEquals(zoom.min,zoom.pinch(1f,.1f),0f)
        assertEquals(2.5f,zoom.pinch(2f,1.25f),0f)
    }
    @Test fun noInventedUltraWideOnOtherPhones() {
        assertEquals(listOf(1f,2f),CameraZoom(1f,4f).presets)
        assertEquals(listOf(.6f,1f,2f,5f),CameraZoom(.6f,8f).presets)
        assertEquals(listOf(1f),CameraZoom().presets)
    }
    @Test fun invalidGesturesCannotReachCameraRequests() {
        val zoom=CameraZoom(.5f,30f)
        assertEquals(2f,zoom.pinch(2f,Float.NaN),0f)
        assertEquals(2f,zoom.pinch(2f,-1f),0f)
        assertEquals(1f,zoom.clamp(Float.POSITIVE_INFINITY),0f)
        assertEquals("1×",CameraZoom.label(1f))
        assertEquals("2.5×",CameraZoom.label(2.5f))
    }
}
