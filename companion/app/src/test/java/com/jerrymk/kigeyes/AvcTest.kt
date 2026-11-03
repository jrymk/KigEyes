package com.jerrymk.kigeyes

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class AvcTest {
    @Test fun splitBothStartCodeLengths() {
        val nals=Avc.nals(byteArrayOf(0,0,0,1,0x67,9,8,0,0,1,0x68,7,0,0,0,1,0x65,6))
        assertEquals(3,nals.size)
        assertArrayEquals(byteArrayOf(0x67,9,8),nals[0]); assertArrayEquals(byteArrayOf(0x68,7),nals[1]); assertArrayEquals(byteArrayOf(0x65,6),nals[2])
    }
    @Test fun splitLengthPrefixedNals() {
        val nals=Avc.nals(byteArrayOf(0,0,0,2,0x67,9,0,0,0,2,0x68,7))
        assertEquals(2,nals.size); assertArrayEquals(byteArrayOf(0x68,7),nals[1])
    }
    @Test fun fragmentedIdrReconstructsExactly() {
        val source=ByteArray(5003) { (it*17).toByte() }; source[0]=0x65
        val packets=Avc.packets(source)
        assertTrue(packets.all { it.size<=1200 })
        assertEquals(128,packets.first()[1].toInt() and 128)
        assertEquals(64,packets.last()[1].toInt() and 64)
        val restored=ByteArrayOutputStream()
        restored.write((packets[0][0].toInt() and 0xe0) or (packets[0][1].toInt() and 31))
        packets.forEach { restored.write(it,2,it.size-2) }
        assertArrayEquals(source,restored.toByteArray())
    }
    @Test fun smallNalNeedsNoFragmentation() {
        val source=byteArrayOf(0x67,3,4,5)
        assertArrayEquals(source,Avc.packets(source).single())
    }
}
