package com.jerrymk.kigeyes

import android.media.*
import android.os.*
import android.view.Surface
import java.io.Closeable

/** A hardware encoder whose input can remain idle without encoding unused video. */
class SurfaceEncoder(val quality: CaptureQuality, private val handler: Handler,
                     private val onFrame: (EncodedFrame)->Unit,private val onFormat: (MediaFormat)->Unit,
                     private val onFailure: (String)->Unit,private val mode: RecordingMode=RecordingMode.HIGH): Closeable {
    private var codec: MediaCodec?=null
    lateinit var surface: Surface; private set
    @Volatile var format: MediaFormat?=null; private set
    @Volatile var frames=0L; private set
    @Volatile var lastFrameMs=0L; private set
    @Volatile var fps=0.0; private set
    @Volatile var name=""; private set
    private var measureTime=0L; private var measureFrames=0L
    private var lastKey=0L
    private var closed=false
    fun start() {
        val media=mediaFormat(quality,mode)
        val info=hardware(media) ?: error("No hardware H.264 encoder supports ${quality.label}")
        name=info.name
        val encoder=MediaCodec.createByCodecName(name); codec=encoder
        encoder.setCallback(object: MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec,index: Int) {}
            override fun onOutputFormatChanged(codec: MediaCodec,output: MediaFormat) { if(!closed) { format=output;onFormat(output) } }
            override fun onError(codec: MediaCodec,e: MediaCodec.CodecException) { if(!closed) onFailure("${quality.label} encoder: ${e.diagnosticInfo}") }
            override fun onOutputBufferAvailable(codec: MediaCodec,index: Int,info: MediaCodec.BufferInfo) {
                try {
                    if(!closed && info.size>0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0) {
                        val buffer=codec.getOutputBuffer(index)!!;buffer.position(info.offset);buffer.limit(info.offset+info.size)
                        val bytes=ByteArray(info.size);buffer.get(bytes)
                        frames++;lastFrameMs=SystemClock.elapsedRealtime()
                        if(lastFrameMs-measureTime>=1000) { fps=(frames-measureFrames)*1000.0/(lastFrameMs-measureTime);measureTime=lastFrameMs;measureFrames=frames }
                        onFrame(EncodedFrame(bytes,info.presentationTimeUs,(info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME)!=0))
                    }
                } catch(e: Exception) { if(!closed) onFailure("Encoder output: ${e.message}") }
                finally { runCatching { codec.releaseOutputBuffer(index,false) } }
            }
        },handler)
        encoder.configure(media,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE)
        surface=encoder.createInputSurface();encoder.start();measureTime=SystemClock.elapsedRealtime()
    }
    fun keyframe() {
        val now=SystemClock.elapsedRealtime()
        if(!closed && now-lastKey>=500) { lastKey=now;runCatching { codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME,0) }) } }
    }
    override fun close() {
        closed=true;runCatching { codec?.stop() };runCatching { codec?.release() };codec=null
        if(::surface.isInitialized) surface.release()
    }
    companion object {
        fun mediaFormat(q: CaptureQuality,mode: RecordingMode=RecordingMode.HIGH)=MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,q.width,q.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            val fps=if(q.isStream)30 else mode.fps
            setInteger(MediaFormat.KEY_BIT_RATE,if(q.isStream)q.bitrate else mode.bitrate(q))
            setInteger(MediaFormat.KEY_FRAME_RATE,fps)
            // Drop before encoding; never discard already compressed interframes.
            // This reduces storage/encoder work, not the camera's 30-fps capture load.
            if(!q.isStream && fps<30)setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER,fps.toFloat())
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1);setInteger(MediaFormat.KEY_MAX_B_FRAMES,0)
            setInteger(MediaFormat.KEY_PROFILE,if(q.isStream) MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline else MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
        }
        fun hardware(format: MediaFormat)=MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull {
            it.isEncoder && it.isHardwareAccelerated && it.supportedTypes.contains(MediaFormat.MIMETYPE_VIDEO_AVC) &&
                runCatching { it.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).isFormatSupported(format) }.getOrDefault(false)
        }
    }
}
