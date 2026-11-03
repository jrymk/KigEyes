package com.jerrymk.kigeyes

import org.junit.Assert.*
import org.junit.Test
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.file.Files
import java.time.ZonedDateTime

class Mp4CaptureTimeTest {
    private fun atom(type: String,payload: ByteArray=byteArrayOf())=ByteBuffer.allocate(8+payload.size).putInt(8+payload.size).put(type.toByteArray()).put(payload).array()
    private val time=ZonedDateTime.parse("2026-10-31T03:51:48+08:00[Asia/Taipei]")
    @Test fun preservesMediaAndStoresExplicitLocalOffset() {
        val file=Files.createTempFile("capture-time",".mp4").toFile()
        try {
            val original=atom("ftyp")+atom("mdat",ByteArray(1024){it.toByte()})+atom("moov")
            file.writeBytes(original)
            RandomAccessFile(file,"rw").use { Mp4CaptureTime.append(it.channel,it.channel,time) }
            val bytes=file.readBytes();assertArrayEquals(original,bytes.copyOfRange(0,original.size))
            val xmp=bytes.copyOfRange(original.size+24,bytes.size).toString(Charsets.UTF_8)
            val doc=javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware=true }.newDocumentBuilder().parse(xmp.byteInputStream())
            val description=doc.getElementsByTagNameNS("http://www.w3.org/1999/02/22-rdf-syntax-ns#","Description").item(0)
            assertEquals("2026-10-31T03:51:48+08:00",description.attributes.getNamedItemNS("http://ns.adobe.com/exif/1.0/","DateTimeOriginal").nodeValue)
            val size=file.length()
            assertThrows(IllegalStateException::class.java) { RandomAccessFile(file,"rw").use { Mp4CaptureTime.append(it.channel,it.channel,time) } }
            assertEquals(size,file.length())
        } finally { file.delete() }
    }
    @Test fun rejectsUnfinishedFilesWithoutChangingThem() {
        for(bytes in listOf(atom("ftyp")+atom("mdat"),atom("ftyp")+ByteBuffer.allocate(8).putInt(0).put("mdat".toByteArray()).array(),atom("ftyp")+byteArrayOf(1,2))) {
            val file=Files.createTempFile("bad-capture",".mp4").toFile()
            try {
                file.writeBytes(bytes)
                assertThrows(IllegalStateException::class.java) { RandomAccessFile(file,"rw").use { Mp4CaptureTime.append(it.channel,it.channel,time) } }
                assertArrayEquals(bytes,file.readBytes())
            } finally { file.delete() }
        }
    }
}
