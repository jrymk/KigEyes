package com.jerrymk.kigeyes

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Adobe XMP UUID box: local capture time with its explicit UTC offset.
 * Appended only to finalized MediaMuxer files; media bytes and chunk offsets never move.
 */
object Mp4CaptureTime {
    private val uuid=UUID.fromString("be7acfcb-97a9-42e8-9c71-999491e3afac")
    fun box(time: ZonedDateTime): ByteArray {
        val date=time.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val xml="""<?xpacket begin="${'\uFEFF'}" id="W5M0MpCehiHzreSzNTczkc9d"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/">
<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
<rdf:Description rdf:about="" xmlns:exif="http://ns.adobe.com/exif/1.0/" xmlns:xmp="http://ns.adobe.com/xap/1.0/"
exif:DateTimeOriginal="$date" xmp:CreateDate="$date" xmp:CreatorTool="KigEyes Camera"/>
</rdf:RDF></x:xmpmeta>
<?xpacket end="w"?>""".toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(24+xml.size).putInt(24+xml.size).put("uuid".toByteArray(Charsets.US_ASCII))
            .putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).put(xml).array()
    }
    fun append(input: FileChannel,output: FileChannel,time: ZonedDateTime) {
        val length=input.size();var position=0L;var movie=false;var media=false;var fileType=false
        fun read(offset: Long,count: Int): ByteBuffer {
            val bytes=ByteBuffer.allocate(count)
            while(bytes.hasRemaining())check(input.read(bytes,offset+bytes.position())>0) { "Truncated MP4 box" }
            bytes.flip();return bytes
        }
        while(position<length) {
            check(length-position>=8) { "Truncated MP4 header" }
            val header=read(position,8);var size=header.int.toLong() and 0xffffffffL
            val type=ByteArray(4).also { header.get(it) }.toString(Charsets.US_ASCII)
            var headerSize=8L
            if(size==1L) { check(length-position>=16);size=read(position+8,8).long;headerSize=16 }
            // An open-ended box would swallow any appended metadata. Do not modify it.
            check(size>=headerSize && size<=length-position) { "Invalid or open-ended MP4 box" }
            if(type=="uuid" && size>=headerSize+16) {
                val id=read(position+headerSize,16)
                check(UUID(id.long,id.long)!=uuid) { "Capture metadata already present" }
            }
            if(type=="ftyp")fileType=true
            if(type=="moov")movie=true
            if(type=="mdat")media=true
            position+=size
        }
        check(fileType && movie && media && output.size()==length) { "MP4 has not finalized" }
        val metadata=ByteBuffer.wrap(box(time))
        try {
            output.position(length)
            while(metadata.hasRemaining())check(output.write(metadata)>0) { "Could not write capture metadata" }
            output.force(true)
        } catch(e: Exception) {
            output.truncate(length);output.force(true);throw e
        }
    }
}
