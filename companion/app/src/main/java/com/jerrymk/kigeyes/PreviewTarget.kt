package com.jerrymk.kigeyes

import android.graphics.SurfaceTexture
import android.view.Surface

/** TextureView gives up ownership on destruction; GL releases its last reference after drawing. */
class PreviewTarget(val texture: SurfaceTexture,@Volatile var displayDegrees: Int=0) {
    val surface=Surface(texture)
    private var references=1
    @Synchronized fun retain(): Boolean { if(references==0) return false; references++;return true }
    @Synchronized fun release() {
        check(references>0)
        if(--references==0) { surface.release();texture.release() }
    }
}
