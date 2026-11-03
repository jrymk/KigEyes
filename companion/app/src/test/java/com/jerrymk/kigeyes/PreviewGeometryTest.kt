package com.jerrymk.kigeyes

import org.junit.Assert.*
import org.junit.Test

class PreviewGeometryTest {
    @Test fun fitsWithoutStretchingOrCropping() {
        for((w,h) in listOf(1600 to 900,900 to 1600,1200 to 480,480 to 1200,0 to 0)) {
            val (x,y)=PreviewGeometry.fit(w,h)
            assertTrue(x<=w && y<=h)
            assertTrue(kotlin.math.abs(x*9-y*16)<=16)
        }
        assertEquals(480 to 270,PreviewGeometry.fit(480,1200))
    }
    @Test fun portraitUsesTheTallViewfinderWithoutStretching() {
        assertEquals(480 to 853,PreviewGeometry.fit(480,1200,90))
        assertEquals(480 to 853,PreviewGeometry.fit(480,1200,270))
        for(rotation in listOf(0,90,180,270)) for((w,h) in listOf(900 to 1600,1600 to 900,600 to 600)) {
            val (x,y)=PreviewGeometry.fit(w,h,rotation)
            assertTrue(x<=w && y<=h)
            val aspect=if(rotation%180==0)16 to 9 else 9 to 16
            assertTrue(kotlin.math.abs(x*aspect.second-y*aspect.first)<=16)
        }
    }
    @Test fun previewTracksDisplayInsteadOfRotatingTwiceWithPhysicalPose() {
        assertEquals(90,PreviewGeometry.rotation(90,0))
        assertEquals(0,PreviewGeometry.rotation(90,90))
        assertEquals(270,PreviewGeometry.rotation(90,180))
        assertEquals(180,PreviewGeometry.rotation(90,270))
        assertEquals(0,PreviewGeometry.rotation(270,270))
    }
}
