package com.jerrymk.kigeyes

/** Some vendor extension JPEG buffers contain megabytes of zero padding. */
object JpegData {
    fun payloadSize(bytes: ByteArray): Int {
        if(bytes.size<4 || bytes[0]!=0xff.toByte() || bytes[1]!=0xd8.toByte())return bytes.size
        var end=bytes.size
        while(end>2 && bytes[end-1]==0.toByte())end--
        // Only strip verified zeros after the final EOI. Never cut at an embedded
        // EXIF thumbnail's EOI or discard appended metadata/gain-map payloads.
        return if(end>=4 && bytes[end-2]==0xff.toByte() && bytes[end-1]==0xd9.toByte())end else bytes.size
    }
}
