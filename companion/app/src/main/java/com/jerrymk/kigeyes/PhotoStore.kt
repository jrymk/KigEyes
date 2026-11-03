package com.jerrymk.kigeyes

import android.content.ContentValues
import android.content.Context
import android.os.StatFs
import android.provider.MediaStore
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

class PhotoStore(private val context: Context) {
    private val worker=Executors.newSingleThreadExecutor()
    @Volatile var status="No photo yet";private set
    fun save(bytes: ByteArray,complete: (String?)->Unit) { worker.execute {
        var uri: android.net.Uri?=null
        try {
            check(StatFs(context.filesDir.path).availableBytes>128L*1024*1024) { "Less than 128 MB storage free" }
            val name="kigeyes-photo-${ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSSXX"))}.jpg"
            val values=ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME,name);put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/KigEyes");put(MediaStore.Images.Media.IS_PENDING,1)
            }
            uri=context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values) ?: error("Cannot create photo")
            context.contentResolver.openOutputStream(uri,"w")!!.use { it.write(bytes,0,JpegData.payloadSize(bytes)) }
            context.contentResolver.update(uri,ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING,0) },null,null)
            status="Saved to Pictures/KigEyes";complete(null)
        } catch(e: Exception) {
            uri?.let { runCatching { context.contentResolver.delete(it,null,null) } }
            status="Photo failed: ${e.message}";complete(status)
        }
    } }
    fun close() { worker.shutdown() }
}
