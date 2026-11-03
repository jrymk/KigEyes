package com.jerrymk.kigeyes

/** Undo Camera2's sensor-to-natural-display rotation for a landscape video output.
 * The SurfaceTexture matrix still applies its buffer crop and GL vertical flip.
 */
object CameraTextureCoordinates {
    fun landscape(sensorDegrees: Int): FloatArray {
        require(sensorDegrees in listOf(0,90,180,270))
        val corners=floatArrayOf(0f,0f,1f,0f,0f,1f,1f,1f)
        for(i in corners.indices step 2) {
            val x=corners[i];val y=corners[i+1]
            val p=when(sensorDegrees) {
                90 -> y to 1f-x
                180 -> 1f-x to 1f-y
                270 -> 1f-y to x
                else -> x to y
            }
            corners[i]=p.first;corners[i+1]=p.second
        }
        return corners
    }
}
