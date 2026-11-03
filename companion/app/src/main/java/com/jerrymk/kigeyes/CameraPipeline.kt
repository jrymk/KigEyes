package com.jerrymk.kigeyes

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.*
import android.os.*
import java.io.Closeable
import java.util.concurrent.Executor

/** Two stable camera outputs: 720p stream/preview and direct high-resolution recording. */
class CameraPipeline(private val context: Context,val recordQuality: CaptureQuality,
    private val photoStore: PhotoStore,private val deviceRotation: ()->Int,
    private val streamFrame: (EncodedFrame)->Unit,private val streamFormat: (MediaFormat)->Unit,
    private val recordFrame: (EncodedFrame)->Unit,private val recordFormatReady: (MediaFormat)->Unit,
    private val failed: (String)->Unit,val recordingMode: RecordingMode=RecordingMode.HIGH,
    enhancedPhotos: Boolean=true,videoDenoise: Boolean=false): Closeable {
    @Volatile var enhancedPhotos=enhancedPhotos;private set
    @Volatile var videoDenoise=videoDenoise;private set
    @Volatile var processingStatus="Waiting for capture metadata";private set
    @Volatile var photoProcessingStatus="No photo yet";private set
    @Volatile var processingCapabilities="Not queried";private set
    @Volatile var extensionCapabilities="Not queried";private set
    fun processing(photos: Boolean,video: Boolean) { handler.post {
        if(closing || fault)return@post
        enhancedPhotos=photos;videoDenoise=video
        if(ready)repeat()
    } }
    private fun resultSummary(result: TotalCaptureResult): String =
        "NR ${result.get(CaptureResult.NOISE_REDUCTION_MODE)} · edge ${result.get(CaptureResult.EDGE_MODE)} · ISO ${result.get(CaptureResult.SENSOR_SENSITIVITY)} · ${((result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0)/100000)/10.0} ms"
    val thread=HandlerThread("camera-encoders").apply { start() }
    private val handler=Handler(thread.looper)
    private var camera: CameraDevice?=null
    private var session: CameraCaptureSession?=null
    private var photoReader: ImageReader?=null
    private var nightPhoto: NightPhoto?=null
    private var nightWaitingFor: CameraCaptureSession?=null
    private var nightStart: (() -> Unit)?=null
    @Volatile var nightCapturing=false;private set
    @Volatile var nightAvailable=false;private set
    private var nightSize: android.util.Size?=null
    private var nightKeys: Set<CaptureRequest.Key<*>> = emptySet()
    private fun cancelNight() {
        nightStart=null;nightWaitingFor=null;nightPhoto?.cancel();nightPhoto=null;nightCapturing=false
    }
    private var photoPending: ((String?)->Unit)?=null
    private var photoCaptured: (()->Unit)?=null
    @Volatile var photoSize: android.util.Size?=null;private set
    @Volatile var sensorOrientation=90;private set
    @Volatile var zoomRange=CameraZoom();private set
    @Volatile var zoomRatio=1f;private set
    @Volatile var measuredZoom=1f;private set
    @Volatile var activeLens="";private set
    private var ratioZoom=false
    private val zoomQueued=java.util.concurrent.atomic.AtomicBoolean(false)
    private val applyZoom=Runnable { zoomQueued.set(false);if(ready && !closing && !fault)repeat() }
    private val captureResults=object: CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession,r: CaptureRequest,result: TotalCaptureResult) {
            processingStatus=resultSummary(result)
            measuredZoom=result.get(CaptureResult.CONTROL_ZOOM_RATIO) ?: zoomRatio
            activeLens=result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID) ?: cameraId
        }
    }
    private var streamEncoder: SurfaceEncoder?=null
    private var recordEncoder: SurfaceEncoder?=null
    @Volatile private var renderer: StreamRenderer?=null
    private var characteristics: CameraCharacteristics?=null
    @Volatile private var running=false
    private var closing=false
    @Volatile var ready=false;private set
    @Volatile var recordingActive=false;private set
    @Volatile var fault=false;private set
    @Volatile var status="Camera stopped";private set
    @Volatile var supportedCombination="Not queried";private set
    @Volatile var availableRecordings="";private set
    @Volatile var streamQuality=CaptureQuality.STREAM_540;private set
    val format get()=streamEncoder?.format
    val recordFormat get()=recordEncoder?.format
    val totalFrames get()=streamEncoder?.frames ?: 0L
    val recordFrames get()=recordEncoder?.frames ?: 0L
    val fps get()=streamEncoder?.fps ?: 0.0
    val recordFps get()=recordEncoder?.fps ?: 0.0
    val codecName get()=streamEncoder?.name ?: ""
    val recordCodecName get()=recordEncoder?.name ?: ""
    val previewAttached get()=renderer?.previewAttached ?: false
    val frameAgeMs get()=streamEncoder?.lastFrameMs?.takeIf { it>0 }?.let { SystemClock.elapsedRealtime()-it } ?: -1L
    private var openedMs=0L
    private var recordStartedMs=0L
    private var cameraId=""
    private var opening=false
    private var openGeneration=0
    @Volatile var recovering=false;private set
    private var recoveryStartedMs=0L
    private val reopen=Runnable { openCamera() }

    @SuppressLint("MissingPermission")
    fun start(previous: Thread?=null) { handler.post {
        try {
            previous?.join(4000);check(previous?.isAlive!=true) { "Previous camera is still closing" }
            if(closing) return@post
            running=true;openedMs=SystemClock.elapsedRealtime()
            val manager=context.getSystemService(CameraManager::class.java)
            val id=manager.cameraIdList.first { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING)==CameraCharacteristics.LENS_FACING_BACK }
            cameraId=id
            characteristics=manager.getCameraCharacteristics(id)
            processingCapabilities="NR modes: ${characteristics!!.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)?.joinToString()} (0 off, 1 fast, 2 high quality)"
            extensionCapabilities=runCatching {
                manager.getCameraExtensionCharacteristics(id).supportedExtensions.joinToString { value -> when(value) {
                    CameraExtensionCharacteristics.EXTENSION_HDR->"HDR"
                    CameraExtensionCharacteristics.EXTENSION_NIGHT->"Night"
                    CameraExtensionCharacteristics.EXTENSION_AUTOMATIC->"Auto"
                    CameraExtensionCharacteristics.EXTENSION_BOKEH->"Bokeh"
                    CameraExtensionCharacteristics.EXTENSION_FACE_RETOUCH->"Retouch"
                    else->value.toString()
                } }.ifEmpty { "None" }
            }.getOrDefault("Unavailable")
            if(Build.VERSION.SDK_INT>=33)runCatching {
                val ext=manager.getCameraExtensionCharacteristics(id)
                nightSize=NightPhoto.supportedSize(ext)
                if(nightSize!=null)nightKeys=ext.getAvailableCaptureRequestKeys(CameraExtensionCharacteristics.EXTENSION_NIGHT)
                nightAvailable=nightSize!=null && CaptureRequest.JPEG_ORIENTATION in nightKeys
            }
            val range=characteristics!!.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            ratioZoom=range!=null
            zoomRange=if(range!=null)CameraZoom(range.lower,range.upper) else CameraZoom(1f,characteristics!!.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f)
            zoomRatio=zoomRange.clamp(zoomRatio)
            sensorOrientation=characteristics!!.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            val map=characteristics!!.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            val sizes=map.getOutputSizes(MediaCodec::class.java)
            availableRecordings=listOf(CaptureQuality.FULL_HD,CaptureQuality.UHD).filter { q ->
                sizes.any { it.width==q.width && it.height==q.height } && SurfaceEncoder.hardware(SurfaceEncoder.mediaFormat(q))!=null
            }.joinToString { it.label }
            check(sizes.any { it.width==recordQuality.width && it.height==recordQuality.height }) { "Camera does not support ${recordQuality.label}" }
            val minDuration=map.getOutputMinFrameDuration(MediaCodec::class.java,android.util.Size(recordQuality.width,recordQuality.height))
            check(minDuration<=34_000_000L) { "${recordQuality.label} cannot run at 30 fps on this camera" }
            // Preserve the established Camera2/preview output. Only the GL
            // encoder target shrinks, avoiding 720p decode+downscale on KigEyes.
            streamQuality=listOf(CaptureQuality.STREAM_540,CaptureQuality.HD).firstOrNull {
                SurfaceEncoder.hardware(SurfaceEncoder.mediaFormat(it))!=null
            } ?: error("No hardware encoder supports the camera stream")
            streamEncoder=SurfaceEncoder(streamQuality,handler,streamFrame,streamFormat,::failure).also { it.start() }
            recordEncoder=SurfaceEncoder(recordQuality,handler,recordFrame,recordFormatReady,::failure,recordingMode).also { it.start() }
            renderer=StreamRenderer(sensorOrientation) { message -> handler.post { failure(message) } }.also { it.start(streamEncoder!!.surface) }
            status="Opening rear camera"
            openCamera()
            handler.postDelayed(watchdog,1000)
        } catch(e: Exception) { failure(e.message ?: e.toString()) }
    } }
    @SuppressLint("MissingPermission")
    private fun openCamera() {
        if(!running || closing || fault || opening) return
        opening=true
        val serial=++openGeneration
        try {
            context.getSystemService(CameraManager::class.java).openCamera(cameraId,object: CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    if(serial!=openGeneration || !running || closing || fault) { device.close();return }
                    opening=false;camera=device;configureSession()
                }
                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    if(serial==openGeneration && running && !closing) recoverCamera()
                }
                override fun onError(device: CameraDevice,code: Int) {
                    device.close()
                    if(serial!=openGeneration || !running || closing) return
                    opening=false
                    if(code==ERROR_CAMERA_IN_USE || code==ERROR_MAX_CAMERAS_IN_USE) recoverCamera()
                    else failure("Camera error $code")
                }
            },handler)
        } catch(e: CameraAccessException) {
            opening=false
            if(e.reason==CameraAccessException.CAMERA_IN_USE || e.reason==CameraAccessException.MAX_CAMERAS_IN_USE) recoverCamera()
            else failure("Open camera: ${e.message}")
        } catch(e: Exception) { opening=false;failure("Open camera: ${e.message}") }
    }
    private fun recoverCamera() {
        if(closing || fault) return
        ++openGeneration;opening=false;ready=false;cancelNight()
        if(!recovering) recoveryStartedMs=SystemClock.elapsedRealtime()
        recovering=true
        photoPending?.invoke("Camera interrupted during photo");photoPending=null
        // Face unlock can evict the rear camera. Keep both codecs and the muxer
        // alive: resumed frames retain their original capture timestamps.
        session?.close();session=null;camera?.close();camera=null
        status="Camera temporarily in use · waiting to resume"
        android.util.Log.i("KigEyesCamera",status)
        handler.removeCallbacks(reopen);handler.postDelayed(reopen,1000)
    }
    private fun configureSession() {
        val device=camera ?: return
        photoReader?.close();photoReader=null;photoSize=null
        val base=listOf(renderer!!.cameraSurface,recordEncoder!!.surface)
        val callback=object: CameraCaptureSession.StateCallback() {
            override fun onConfigured(value: CameraCaptureSession) {
                if(!running || closing || camera!==device) { value.close();return }
                session=value;ready=true;recovering=false
                openedMs=SystemClock.elapsedRealtime();recordStartedMs=openedMs
                repeat();streamEncoder?.keyframe();recordEncoder?.keyframe()
            }
            override fun onClosed(value: CameraCaptureSession) {
                if(nightWaitingFor===value) {
                    nightWaitingFor=null
                    val launch=nightStart;nightStart=null
                    if(!closing && !fault && camera===device)launch?.invoke()
                }
            }
            override fun onConfigureFailed(value: CameraCaptureSession) { if(!closing && camera===device) failure("Camera rejected 720p streaming + ${recordQuality.label} recording") }
        }
        try {
            val executor=Executor { handler.post(it) }
            val sizes=characteristics!!.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!.getOutputSizes(android.graphics.ImageFormat.JPEG)
                .filter { it.width.toLong()*it.height<=16_000_000 }.sortedByDescending { it.width.toLong()*it.height }.distinct()
            for(size in sizes) {
                val reader=ImageReader.newInstance(size.width,size.height,android.graphics.ImageFormat.JPEG,2)
                val candidate=SessionConfiguration(SessionConfiguration.SESSION_REGULAR,(base+reader.surface).map { OutputConfiguration(it) },executor,callback)
                if(runCatching { device.isSessionConfigurationSupported(candidate) }.getOrDefault(false)) {
                    photoReader=reader;photoSize=size;break
                }
                reader.close()
            }
            photoReader?.setOnImageAvailableListener({ reader ->
                val image=reader.acquireNextImage() ?: return@setOnImageAvailableListener
                val bytes=try { image.planes[0].buffer.let { b -> ByteArray(b.remaining()).also { b.get(it) } } } finally { image.close() }
                val callbackPhoto=photoPending;photoPending=null
                if(callbackPhoto!=null) { photoCaptured?.invoke();photoCaptured=null;photoStore.save(bytes,callbackPhoto) }
            },handler)
            val outputs=base+listOfNotNull(photoReader?.surface)
            val config=SessionConfiguration(SessionConfiguration.SESSION_REGULAR,outputs.map { OutputConfiguration(it) },executor,callback)
            supportedCombination=runCatching { device.isSessionConfigurationSupported(config).toString() }.getOrDefault("Query unavailable; testing actual session")
            check(supportedCombination!="false") { "Camera does not support this simultaneous output combination" }
            device.createCaptureSession(config)
        } catch(e: Exception) { failure("Camera session: ${e.message}") }
    }
    private fun repeat() {
        val device=camera ?: return;val active=session ?: return
        if(!running || closing || nightCapturing) return
        try {
            val request=device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(renderer!!.cameraSurface)
                if(recordingActive) addTarget(recordEncoder!!.surface)
                set(CaptureRequest.CONTROL_MODE,CaptureRequest.CONTROL_MODE_AUTO)
                CameraProcessing.apply(this,characteristics!!,photo=false,enhanced=videoDenoise)
                applyZoomTo(this)
                val af=characteristics!!.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
                if(af.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)) set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                val ranges=characteristics!!.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
                ranges.filter { it.upper==30 }.maxByOrNull { it.lower }?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,it) }
            }.build()
            active.setRepeatingRequest(request,captureResults,handler)
            status="Stream ${streamQuality.label} · record ${recordQuality.label}${if(recordingActive) " active" else " ready"}"
        } catch(e: Exception) { failure("Camera request: ${e.message}") }
    }
    fun setZoom(value: Float) {
        if(!value.isFinite() || closing || fault)return
        zoomRatio=zoomRange.clamp(value)
        // Keep only the latest value, at most one request per camera frame.
        if(zoomQueued.compareAndSet(false,true))handler.postDelayed(applyZoom,33)
    }
    private fun applyZoomTo(request: CaptureRequest.Builder) {
        if(ratioZoom)request.set(CaptureRequest.CONTROL_ZOOM_RATIO,zoomRatio)
        else characteristics?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.let { area ->
            val w=(area.width()/zoomRatio).toInt().coerceAtLeast(1);val h=(area.height()/zoomRatio).toInt().coerceAtLeast(1)
            val x=area.left+(area.width()-w)/2;val y=area.top+(area.height()-h)/2
            request.set(CaptureRequest.SCALER_CROP_REGION,android.graphics.Rect(x,y,x+w,y+h))
        }
    }
    fun record(active: Boolean) { handler.post {
        if(closing || fault) return@post
        recordingActive=active;if(active) recordStartedMs=SystemClock.elapsedRealtime()
        if(ready) { repeat();if(active) recordEncoder?.keyframe() }
    } }
    fun snapshot(captured: ()->Unit={},complete: (String?)->Unit) { handler.post {
        if(photoPending!=null) { complete("Photo already pending");return@post }
        try {
            check(ready && !recovering && !closing && !fault) { "Camera not ready" }
            val reader=photoReader ?: error("Photo not supported in this camera mode")
            val request=camera!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface);set(CaptureRequest.JPEG_QUALITY,95.toByte())
                set(CaptureRequest.JPEG_ORIENTATION,(sensorOrientation+deviceRotation())%360)
                applyZoomTo(this)
                set(CaptureRequest.CONTROL_MODE,CaptureRequest.CONTROL_MODE_AUTO)
                val af=characteristics!!.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
                if(af.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO))set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                CameraProcessing.apply(this,characteristics!!,photo=true,enhanced=enhancedPhotos)
                if(enhancedPhotos && !recordingActive && CaptureRequest.CONTROL_ENABLE_ZSL in characteristics!!.availableCaptureRequestKeys)
                    set(CaptureRequest.CONTROL_ENABLE_ZSL,true)
            }.build()
            photoPending=complete
            photoCaptured=captured
            session!!.capture(request,object: CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession,r: CaptureRequest,result: TotalCaptureResult) {
                    photoProcessingStatus=resultSummary(result)
                }
                override fun onCaptureFailed(s: CameraCaptureSession,r: CaptureRequest,f: CaptureFailure) {
                    if(photoPending===complete) { photoPending=null;photoCaptured=null;complete("Photo capture failed (${f.reason})") }
                }
            },handler)
            handler.postDelayed({ if(photoPending===complete) { photoPending=null;photoCaptured=null;complete("Photo capture timed out") } },10000)
        } catch(e: Exception) { photoPending=null;photoCaptured=null;complete(e.message ?: "Photo failed") }
    } }
    /** Explicit slower photo mode; never interrupts an active local recording. */
    fun snapshotNight(captured: ()->Unit={},complete: (String?)->Unit) { handler.post {
        try {
            check(Build.VERSION.SDK_INT>=33 && nightAvailable) { "Night photos unavailable on this camera" }
            check(ready && !recovering && !closing && !fault && !nightCapturing) { "Camera not ready" }
            check(!recordingActive) { "Stop recording before taking a Night photo" }
            check(photoPending==null) { "Photo already pending" }
            check(zoomRatio==1f || CaptureRequest.CONTROL_ZOOM_RATIO in nightKeys) { "Use 1x zoom for Night photos on this phone" }
            val device=camera!!;val previous=session!!
            ready=false;nightCapturing=true;photoPending=complete
            status="Night photo · live stream pauses briefly"
            val capture=NightPhoto(device,handler,renderer!!.cameraSurface,nightSize!!,nightKeys,
                (sensorOrientation+deviceRotation())%360,zoomRatio) { bytes,problem,reopen ->
                nightPhoto=null;nightCapturing=false;nightWaitingFor=null;nightStart=null
                val callback=photoPending;photoPending=null
                if(!closing && !fault && camera===device) {
                    if(reopen)recoverCamera() else configureSession()
                }
                if(callback!=null) {
                    if(bytes!=null) {
                        photoProcessingStatus="Vendor Night extension"
                        captured();photoStore.save(bytes,callback)
                    } else callback(problem ?: "Night photo returned no image")
                }
            }
            nightPhoto=capture;nightWaitingFor=previous;nightStart={ capture.start() }
            previous.stopRepeating();previous.close();session=null
            handler.postDelayed({
                if(nightWaitingFor===previous && !closing)recoverCamera()
            },4000)
        } catch(e: Exception) {
            if(nightCapturing) {
                photoPending=null;cancelNight()
                if(!closing && !fault)recoverCamera()
            }
            complete(e.message ?: "Night photo failed")
        }
    } }
    fun keyframe() { handler.post { streamEncoder?.keyframe() } }
    fun recordKeyframe() { handler.post { recordEncoder?.keyframe() } }
    fun preview(target: PreviewTarget?) { renderer?.preview(target) }
    private val watchdog=object: Runnable {
        override fun run() {
            if(!running || closing || fault) return
            val now=SystemClock.elapsedRealtime()
            if(nightCapturing) { handler.postDelayed(this,1000);return }
            if(recovering) {
                if(now-recoveryStartedMs>30000) { failure("Camera unavailable for 30 seconds · recording saved; reopen to retry");return }
                handler.postDelayed(this,1000);return
            }
            if(now-openedMs>15000 && frameAgeMs.let { it<0 || it>5000 }) { failure("Camera frames stopped · recording saved; reopen the camera to retry");return }
            if(recordingActive && now-recordStartedMs>5000 && now-(recordEncoder?.lastFrameMs ?: 0)>5000) {
                failure("Recording encoder stopped producing frames · reopen the camera to retry");return
            }
            handler.postDelayed(this,1000)
        }
    }
    private fun failure(message: String) {
        if(closing || fault) return
        fault=true;ready=false;status=message;failed(message)
    }
    override fun close() {
        handler.post {
            if(closing) return@post
            closing=true;running=false;ready=false;cancelNight();handler.removeCallbacks(watchdog);handler.removeCallbacks(reopen)
            handler.removeCallbacks(applyZoom)
            photoPending?.invoke("Camera stopped before photo completed");photoPending=null
            photoReader?.close();photoReader=null
            runCatching { session?.stopRepeating() };runCatching { session?.close() };session=null
            runCatching { camera?.close() };camera=null
            val gl=renderer;renderer=null
            val finish={ handler.post {
                recordEncoder?.close();streamEncoder?.close()
                recordEncoder=null;streamEncoder=null;thread.quitSafely()
            };Unit }
            // Keep draining codec callbacks while GL releases its encoder surface.
            // Blocking this handler on EGL can otherwise deadlock a full encoder queue.
            if(gl!=null) gl.shutdown(finish) else finish()
        }
    }
}
