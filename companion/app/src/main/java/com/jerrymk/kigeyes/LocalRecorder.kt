package com.jerrymk.kigeyes

import android.content.*
import android.media.*
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import java.io.Closeable
import java.nio.ByteBuffer
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicLong

/** Disk I/O never runs on the encoder or network threads. Overload stops with an explicit error. */
class LocalRecorder(private val context: Context, private val changed: () -> Unit) : Closeable {
    private val work=LinkedBlockingQueue<() -> Unit>()
    private val bytesQueued=AtomicLong()
    private val thread=Thread({ while(true) { val job=work.take(); job(); if(closed && work.isEmpty()) break } },"local-recording").apply { start() }
    @Volatile private var closed=false
    @Volatile var accepting=false; private set
    @Volatile var busy=false; private set
    @Volatile var status="Not recording"; private set
    @Volatile var durationUs=0L; private set
    @Volatile var frames=0L; private set
    private var muxer: MediaMuxer?=null
    private var fd: ParcelFileDescriptor?=null
    private var uri: Uri?=null
    private var track=-1; private var firstPts=-1L; private var lastSpaceCheck=0L
    private var capturedAt=ZonedDateTime.now()
    private var timeline=RecordingTimeline()
    @Volatile var playbackDurationUs=0L;private set

    @Synchronized fun start(format: MediaFormat,rotation: Int=0,mode: RecordingMode=RecordingMode.HIGH) {
        if(busy || closed) return
        busy=true;accepting=true; status="Starting recording…"; durationUs=0;playbackDurationUs=0; frames=0
        capturedAt=ZonedDateTime.now()
        work.put {
            try {
                check(StatFs(context.filesDir.path).availableBytes>512L*1024*1024) { "Less than 512 MB free" }
                val name="kigeyes-phone-${capturedAt.format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssXX"))}.mp4"
                val values=ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME,name); put(MediaStore.Video.Media.MIME_TYPE,"video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH,"Movies/KigEyes"); put(MediaStore.Video.Media.IS_PENDING,1)
                    put(MediaStore.Video.Media.DATE_TAKEN,capturedAt.toInstant().toEpochMilli())
                }
                uri=context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,values) ?: error("Cannot create video")
                context.getSharedPreferences("recovery",0).edit().putString("pending",uri.toString()).apply()
                fd=context.contentResolver.openFileDescriptor(uri!!,"rw") ?: error("Cannot open video")
                muxer=MediaMuxer(fd!!.fileDescriptor,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).apply { setOrientationHint(rotation);track=addTrack(MediaFormat(format).apply { setInteger(MediaFormat.KEY_FRAME_RATE,mode.fps*mode.speed) }); start() }
                timeline=RecordingTimeline(mode.speed);firstPts=-1; lastSpaceCheck=0; status="Recording · waiting for keyframe"
            } catch(e: Exception) { accepting=false; finish("Recording failed: ${e.message}") }
            changed()
        }
    }
    @Synchronized fun offer(frame: EncodedFrame) {
        if(!accepting || closed) return
        if(bytesQueued.get()+frame.bytes.size>32L*1024*1024 || work.size>120) {
            stop("Storage too slow · recording stopped"); return
        }
        bytesQueued.addAndGet(frame.bytes.size.toLong())
        work.put {
            try {
                val writer=muxer
                if(writer!=null && (firstPts>=0 || frame.key)) {
                    if(firstPts<0) firstPts=frame.ptsUs
                    val pts=timeline.timestamp(frame.ptsUs)
                    writer.writeSampleData(track,ByteBuffer.wrap(frame.bytes),MediaCodec.BufferInfo().apply {
                        set(0,frame.bytes.size,pts,if(frame.key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    })
                    durationUs=frame.ptsUs-firstPts;playbackDurationUs=pts; frames++; status="Recording"
                    val now=SystemClock.elapsedRealtime()
                    if(now-lastSpaceCheck>1000) {
                        lastSpaceCheck=now
                        if(StatFs(context.filesDir.path).availableBytes<256L*1024*1024) stop("Storage nearly full · recording saved")
                    }
                }
            } catch(e: Exception) { accepting=false; finish("Recording error: ${e.message}") }
            finally { bytesQueued.addAndGet(-frame.bytes.size.toLong()) }
        }
    }
    @Synchronized fun stop(message: String?=null) {
        if(!accepting) return
        accepting=false; status="Saving…"; work.put { finish(message); changed() }
    }
    private fun finish(message: String?) {
        var valid=false
        try {
            if(muxer!=null && frames>0) { muxer!!.stop(); valid=true }
        } catch(e: Exception) { status="Could not finalize video: ${e.message}" }
        finally { runCatching { muxer?.release() }; muxer=null; runCatching { fd?.close() }; fd=null }
        uri?.let {
            if(valid) {
                val metadataError=runCatching {
                    ParcelFileDescriptor.AutoCloseInputStream(context.contentResolver.openFileDescriptor(it,"r") ?: error("Cannot read video metadata")).use { input ->
                        ParcelFileDescriptor.AutoCloseOutputStream(context.contentResolver.openFileDescriptor(it,"rw") ?: error("Cannot write video metadata")).use { output ->
                            Mp4CaptureTime.append(input.channel,output.channel,capturedAt)
                        }
                    }
                }.exceptionOrNull()
                runCatching { context.contentResolver.update(it,ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING,0);put(MediaStore.Video.Media.DATE_TAKEN,capturedAt.toInstant().toEpochMilli())
                },null,null) }
                    .onFailure { status="Video finalized, but gallery update failed: ${it.message}" }
                    .onSuccess { status=if(metadataError!=null) "Saved video; capture date metadata failed: ${metadataError.message}" else message ?: "Saved to Movies/KigEyes"; context.getSharedPreferences("recovery",0).edit().remove("pending").apply() }
            } else if(frames==0L) {
                runCatching { context.contentResolver.delete(it,null,null) }
                context.getSharedPreferences("recovery",0).edit().remove("pending").apply()
                status=message ?: "No frames captured"
            } else status="Unfinished recording retained; needs recovery"
        }
        if(uri==null) status=message ?: "Not recording"
        uri=null
        busy=false
    }
    override fun close() {
        stop(); closed=true; work.put {}; thread.join(5000)
    }
}
