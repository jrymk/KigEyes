package com.jerrymk.kigeyes

import org.junit.Assert.assertEquals
import org.junit.Test

class JpegDataTest {
    private fun data(vararg values: Int)=values.map { it.toByte() }.toByteArray()
    @Test fun stripsOnlyPaddingAfterFinalImage() {
        val jpeg=data(0xff,0xd8,1,0xff,0xd9,2,0xff,0xd9)
        assertEquals(jpeg.size,JpegData.payloadSize(jpeg.copyOf(10000)))
        assertEquals(jpeg.size,JpegData.payloadSize(jpeg))
    }
    @Test fun retainsUnrecognizedTrailersAndIncompleteImages() {
        for(bytes in listOf(data(0xff,0xd8,1,0xff,0xd9,3,0),data(0xff,0xd8,1,0),data(1,0,0),byteArrayOf()))
            assertEquals(bytes.size,JpegData.payloadSize(bytes))
    }
}
