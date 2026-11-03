package com.jerrymk.kigeyes

import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.ExtensionSessionConfiguration
import android.hardware.camera2.params.OutputConfiguration
import android.media.ImageReader
import android.os.Handler
import android.util.Size
import android.view.Surface
import java.util.concurrent.Executor

/** One explicitly requested vendor Night capture. The pipeline remains the device owner. */
@android.annotation.TargetApi(33)
class NightPhoto(private val device: CameraDevice,private val handler: Handler,
    private val preview: Surface,private val size: Size,private val keys: Set<CaptureRequest.Key<*>>,
    private val orientation: Int,private val zoom: Float,
    private val complete: (ByteArray?,String?,Boolean)->Unit) {
    private val executor=Executor { handler.post(it) }
    private var reader: ImageReader?=null
    private var session: CameraExtensionSession?=null
    private var finishing=false
    private var delivered=false
    private var bytes: ByteArray?=null
    private var error: String?=null
    private val timeout=Runnable { finish(null,"Night photo timed out") }
    private val closeTimeout=Runnable { deliver(true) }
    private val capture=Runnable {
        if(!finishing && !delivered)try {
            val request=device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader!!.surface)
                configure(this)
            }.build()
            session!!.capture(request,executor,object: CameraExtensionSession.ExtensionCaptureCallback() {
                override fun onCaptureFailed(s: CameraExtensionSession,r: CaptureRequest) { finish(null,"Night photo capture failed") }
                override fun onCaptureSequenceAborted(s: CameraExtensionSession,id: Int) { finish(null,"Night photo capture aborted") }
            })
        } catch(e: Exception) { finish(null,"Night photo: ${e.message}") }
    }
    private fun configure(request: CaptureRequest.Builder) {
        if(CaptureRequest.JPEG_ORIENTATION in keys)request.set(CaptureRequest.JPEG_ORIENTATION,orientation)
        if(CaptureRequest.JPEG_QUALITY in keys)request.set(CaptureRequest.JPEG_QUALITY,95.toByte())
        if(CaptureRequest.CONTROL_ZOOM_RATIO in keys)request.set(CaptureRequest.CONTROL_ZOOM_RATIO,zoom)
    }
    fun start() {
        handler.postDelayed(timeout,30000)
        try {
            reader=ImageReader.newInstance(size.width,size.height,ImageFormat.JPEG,2).also { output ->
                output.setOnImageAvailableListener({ source ->
                    val image=source.acquireNextImage() ?: return@setOnImageAvailableListener
                    val data=try { image.planes[0].buffer.let { b->ByteArray(b.remaining()).also { b.get(it) } } } finally { image.close() }
                    finish(data,null)
                },handler)
            }
            device.createExtensionSession(ExtensionSessionConfiguration(CameraExtensionCharacteristics.EXTENSION_NIGHT,
                listOf(OutputConfiguration(preview),OutputConfiguration(reader!!.surface)),executor,
                object: CameraExtensionSession.StateCallback() {
                    override fun onConfigured(value: CameraExtensionSession) {
                        session=value
                        if(finishing || delivered) { value.close();return }
                        try {
                            val request=device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(preview);configure(this) }.build()
                            value.setRepeatingRequest(request,executor,object: CameraExtensionSession.ExtensionCaptureCallback() {})
                            // Let the extension establish its own exposure/preview before capture.
                            handler.postDelayed(capture,1000)
                        } catch(e: Exception) { finish(null,"Night preview: ${e.message}") }
                    }
                    override fun onConfigureFailed(value: CameraExtensionSession) { session=value;finish(null,"Phone rejected Night photo session") }
                    override fun onClosed(value: CameraExtensionSession) {
                        if(!finishing && !delivered) { error="Night session closed before capture";finishing=true }
                        deliver(false)
                    }
                }))
        } catch(e: Exception) { finish(null,"Night session: ${e.message}") }
    }
    private fun finish(data: ByteArray?,problem: String?) {
        if(finishing || delivered)return
        finishing=true;bytes=data;error=problem
        handler.removeCallbacks(timeout);handler.removeCallbacks(capture)
        runCatching { session?.close() }
        // A vendor can fail to close its extension; the owner then reopens the device.
        handler.postDelayed(closeTimeout,3000)
    }
    private fun deliver(reopen: Boolean) {
        if(delivered)return
        delivered=true;handler.removeCallbacks(timeout);handler.removeCallbacks(capture);handler.removeCallbacks(closeTimeout)
        reader?.close();reader=null
        complete(bytes,error,reopen)
    }
    fun cancel() {
        if(delivered)return
        delivered=true;handler.removeCallbacks(timeout);handler.removeCallbacks(capture);handler.removeCallbacks(closeTimeout)
        runCatching { session?.close() };reader?.close();reader=null
    }
    companion object {
        fun supportedSize(c: CameraExtensionCharacteristics): Size? {
            val night=CameraExtensionCharacteristics.EXTENSION_NIGHT
            if(night !in c.supportedExtensions)return null
            if(Size(1280,720) !in c.getExtensionSupportedSizes(night,SurfaceTexture::class.java))return null
            return c.getExtensionSupportedSizes(night,ImageFormat.JPEG)
                .filter { it.width.toLong()*it.height<=16_000_000 }.maxByOrNull { it.width.toLong()*it.height }
        }
    }
}
