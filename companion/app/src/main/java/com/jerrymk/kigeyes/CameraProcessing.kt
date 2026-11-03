package com.jerrymk.kigeyes

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest

/** Only request advertised processing modes; retain template defaults otherwise. */
object CameraProcessing {
    fun choose(available: IntArray?,preferred: Int): Int?=preferred.takeIf { available?.contains(it)==true }
    fun apply(builder: CaptureRequest.Builder,c: CameraCharacteristics,photo: Boolean,enhanced: Boolean) {
        if(!enhanced)return
        val writable=c.availableCaptureRequestKeys.toSet()
        fun mode(key: CaptureRequest.Key<Int>,supported: CameraCharacteristics.Key<IntArray>,value: Int) {
            if(key in writable)choose(c.get(supported),value)?.let { builder.set(key,it) }
        }
        mode(CaptureRequest.NOISE_REDUCTION_MODE,CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES,
            CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
        if(photo) {
            mode(CaptureRequest.EDGE_MODE,CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES,CaptureRequest.EDGE_MODE_HIGH_QUALITY)
            mode(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES,
                CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY)
        }
    }
}
