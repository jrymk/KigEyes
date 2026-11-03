package com.jerrymk.kigeyes

import java.io.ByteArrayOutputStream

data class EncodedFrame(val bytes: ByteArray, val ptsUs: Long, val key: Boolean)

/** MediaCodec AVC output uses Annex B on Android; also accept length-prefixed AVC. */
object Avc {
    fun nals(data: ByteArray): List<ByteArray> {
        fun prefix(i: Int): Int = when {
            i + 3 < data.size && data[i] == 0.toByte() && data[i+1] == 0.toByte() && data[i+2] == 0.toByte() && data[i+3] == 1.toByte() -> 4
            i + 2 < data.size && data[i] == 0.toByte() && data[i+1] == 0.toByte() && data[i+2] == 1.toByte() -> 3
            else -> 0
        }
        if (prefix(0) > 0) {
            val result = mutableListOf<ByteArray>(); var start = prefix(0); var i = start
            while (i < data.size) {
                val n = prefix(i)
                if (n > 0) { if (i > start) result.add(data.copyOfRange(start, i)); start = i+n; i = start }
                else i++
            }
            if (start < data.size) result.add(data.copyOfRange(start, data.size))
            return result
        }
        val result = mutableListOf<ByteArray>(); var i = 0
        while (i + 4 <= data.size) {
            val n = ((data[i].toInt() and 255) shl 24) or ((data[i+1].toInt() and 255) shl 16) or ((data[i+2].toInt() and 255) shl 8) or (data[i+3].toInt() and 255)
            if (n <= 0 || n > data.size-i-4) return listOf(data)
            result.add(data.copyOfRange(i+4, i+4+n)); i += 4+n
        }
        return if (i == data.size && result.isNotEmpty()) result else listOf(data)
    }

    fun packets(nal: ByteArray, maxPayload: Int = 1200): List<ByteArray> {
        require(maxPayload > 2)
        if (nal.isEmpty()) return emptyList()
        if (nal.size <= maxPayload) return listOf(nal)
        val packets = mutableListOf<ByteArray>(); var i = 1
        while (i < nal.size) {
            val end = minOf(i+maxPayload-2, nal.size)
            val part = ByteArray(end-i+2)
            part[0] = ((nal[0].toInt() and 0xe0) or 28).toByte()
            part[1] = ((nal[0].toInt() and 31) or (if(i==1) 128 else 0) or (if(end==nal.size) 64 else 0)).toByte()
            nal.copyInto(part, 2, i, end); packets.add(part); i=end
        }
        return packets
    }
}
