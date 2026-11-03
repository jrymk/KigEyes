package com.jerrymk.kigeyes

import org.junit.Assert.*
import org.junit.Test

class CameraTextureCoordinatesTest {
    @Test fun pixelCameraTransformPreservesLandscapeGeometry() {
        // Actual Pixel 8 Pro SurfaceTexture matrix maps (u,v) -> (1-v,1-u).
        val uv=CameraTextureCoordinates.landscape(90)
        val sampled=FloatArray(8)
        for(i in uv.indices step 2) { sampled[i]=1f-uv[i+1];sampled[i+1]=1f-uv[i] }
        assertArrayEquals(floatArrayOf(0f,1f,1f,1f,0f,0f,1f,0f),sampled,0.0001f)
    }
    @Test fun allSensorMountingsKeepEveryCornerExactlyOnce() {
        for(angle in listOf(0,90,180,270)) {
            val uv=CameraTextureCoordinates.landscape(angle)
            assertEquals(setOf(0f to 0f,0f to 1f,1f to 0f,1f to 1f),uv.toList().chunked(2).map { it[0] to it[1] }.toSet())
        }
    }
}
