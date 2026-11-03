package com.jerrymk.kigeyes

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.media.MediaFormat
import android.os.*
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

class CameraService: Service() {
    inner class LocalBinder: Binder() { val service get()=this@CameraService }
    private val binder=LocalBinder()
    private val handler=Handler(Looper.getMainLooper())
    private val networkWorker=Executors.newSingleThreadExecutor()
    private lateinit var connectionOwner: CompanionConnection
    internal val connections get() = connectionOwner
    val wifi get() = connectionOwner.wifi
    lateinit var recorder: LocalRecorder;private set
    val companion get() = connectionOwner.companion
    val ble get() = connectionOwner.ble
    lateinit var pose: PhonePose;private set
    lateinit var photos: PhotoStore;private set
    private val phoneSession=UUID.randomUUID().toString()
    @Volatile private var remoteAck: JSONObject?=null
    @Volatile private var remoteZoomAck=""
    @Volatile private var remoteZoomError=""
    private var recordRotation=0
    @Volatile var photoBusy=false;private set
    private var provisioning=false
    private var provisioningGeneration=0
    var pipeline: CameraPipeline?=null;private set
    @Volatile var server: RtspServer?=null;private set
    @Volatile var error="";private set
    @Volatile var recordRequested=false;private set
    @Volatile private var recordOpened=false
    @Volatile private var generation=0
    private var sps: ByteArray?=null;private var pps: ByteArray?=null
    private var wake: PowerManager.WakeLock?=null
    private var closingCamera: Thread?=null
    private var lastNotification=""
    private var destroying=false
    private val token by lazy { getSharedPreferences("settings",0).let { p -> p.getString("token",null) ?: UUID.randomUUID().toString().replace("-","").also { p.edit().putString("token",it).apply() } } }
    val freeBytes get()=StatFs(filesDir.path).availableBytes
    val thermalStatus get()=getSystemService(PowerManager::class.java).currentThermalStatus
    val recordingBlockedReason get()=if(photoBusy) "Wait for photo to finish" else RecordingPolicy.startError(
        pipeline?.let { it.ready && !it.fault && !it.recovering }==true,recorder.busy,freeBytes,thermalStatus)
    val thermalLabel get()=when(thermalStatus) {0->"Normal";1->"Warm";2->"Moderate";3->"Severe";4->"Critical";5->"Emergency";else->"Shutdown"}
    override fun onBind(intent: Intent)=binder
    override fun onCreate() {
        super.onCreate();recorder=LocalRecorder(this) {}
        pose=PhonePose(this);photos=PhotoStore(this)
        error=getSharedPreferences("recovery",0).getString("cameraError","") ?: ""
        connectionOwner=CompanionConnection(this, { address ->
            if(!destroying) networkWorker.execute {
                server?.close();server=null
                if(address!=null && !destroying) try { server=RtspServer(address,token) { pipeline?.keyframe() }.also { it.sps=sps;it.pps=pps } }
                catch(e: Exception) { error="Stream listener: ${e.message}" }
                if(!destroying && ::connectionOwner.isInitialized) companion.networkChanged()
                if(address!=null && provisioning) handler.post { finishProvisioning() }
            }
        }, {token}, ::phoneTelemetry, ::remoteCommand, ::remoteZoom,
            { success -> if(!success) handler.post { finishProvisioning() } })
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("capture","Camera capture",NotificationManager.IMPORTANCE_LOW))
        if(getSharedPreferences("recovery",0).contains("pending")) error="An earlier recording was not finalized. Its pending file has been retained."
        handler.post(tick)
    }
    private fun notification(): Notification {
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        val stop=PendingIntent.getService(this,1,Intent(this,CameraService::class.java).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE)
        val recording=PendingIntent.getService(this,2,Intent(this,CameraService::class.java).setAction("STOP_RECORD"),PendingIntent.FLAG_IMMUTABLE)
        val text=if(provisioning && pipeline==null) ble.message else if(recordRequested) "Recording ${pipeline?.recordQuality?.label} · ${recorder.durationUs/1_000_000}s · stream ${pipeline?.streamQuality?.label ?: "540p30"}" else "Camera streaming · ${pipeline?.streamQuality?.label ?: "540p30"} · ${server?.connections ?: 0} viewers"
        val builder=Notification.Builder(this,"capture").setContentTitle("KigEyes · Camera").setContentText(text)
            .setSmallIcon(android.R.drawable.presence_video_online).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null,"Stop camera",stop).build())
        if(recordRequested) builder.addAction(Notification.Action.Builder(null,"Stop & save",recording).build())
        return builder.build()
    }
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        if(intent?.action=="STOP") { stopCamera();return START_NOT_STICKY }
        if(intent?.action=="STOP_RECORD") { if(recordRequested) record();return START_NOT_STICKY }
        if(intent?.action=="PROVISION") {
            provisioning=true
            val attempt=++provisioningGeneration
            startForeground(1,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or if(pipeline!=null) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0)
            connectionOwner.provision()
            handler.postDelayed({ if(provisioning && attempt==provisioningGeneration) { ble.cancel();finishProvisioning() } },150000)
            return START_NOT_STICKY
        }
        startForeground(1,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        if(pipeline==null) {
            val quality=CaptureQuality.recording(intent?.getStringExtra("recordQuality"))
            val prefs=getSharedPreferences("captureProcessing",0)
            val mode=RecordingMode.parse(intent?.getStringExtra("recordingMode") ?: prefs.getString("recordingMode",null))
            error="";recordRequested=false;recordOpened=false
            getSharedPreferences("recovery",0).edit().remove("cameraError").apply()
            val serial=++generation
            pose.enable()
            wake=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"KigEyes:camera").apply { setReferenceCounted(false);acquire(10*60*1000L) }
            pipeline=CameraPipeline(this,quality,photos,{pose.deviceDegrees},
                { frame -> if(serial==generation) server?.offer(frame) },
                { format -> if(serial==generation) updateStreamFormat(format) },
                { frame -> if(serial==generation && recordRequested) recorder.offer(frame) },
                { format -> handler.post {
                    // Record/Stop and first-format arrival are serialized on the main thread.
                    if(serial==generation && recordRequested && !recordOpened) {
                        recorder.start(format,recordRotation,mode);recordOpened=recorder.accepting;pipeline?.recordKeyframe()
                    }
                } },
                { message -> handler.post { if(serial==generation) {
                    error=message
                    getSharedPreferences("recovery",0).edit().putString("cameraError",message).apply()
                    android.util.Log.e("KigEyesCamera",message)
                    stopCamera()
                } } },mode,prefs.getBoolean("enhancedPhotos",true),prefs.getBoolean("videoDenoise",false)
            ).also { it.start(closingCamera) };closingCamera=null
        }
        return START_NOT_STICKY
    }
    private fun updateStreamFormat(format: MediaFormat) {
        val nals=listOf("csd-0","csd-1").flatMap { name -> format.getByteBuffer(name)?.duplicate()?.let { b -> val bytes=ByteArray(b.remaining());b.get(bytes);Avc.nals(bytes) } ?: emptyList() }
        sps=nals.firstOrNull { it.isNotEmpty() && (it[0].toInt() and 31)==7 };pps=nals.firstOrNull { it.isNotEmpty() && (it[0].toInt() and 31)==8 }
        server?.let { it.sps=sps;it.pps=pps }
    }
    fun processing(photos: Boolean,video: Boolean) {
        getSharedPreferences("captureProcessing",0).edit().putBoolean("enhancedPhotos",photos).putBoolean("videoDenoise",video).apply()
        pipeline?.processing(photos,video)
    }
    fun preview(target: PreviewTarget?) { pipeline?.preview(target) }
    fun disconnectConnection() { connectionOwner.disconnect();if(provisioning)finishProvisioning() }
    private fun finishProvisioning() {
        provisioning=false;provisioningGeneration++
        if(pipeline==null) { stopForeground(STOP_FOREGROUND_REMOVE);stopSelf() }
    }
    fun stopCamera() {
        ble.cancel();provisioning=false;provisioningGeneration++
        generation++;recordRequested=false;recordOpened=false;recorder.stop();pose.disable()
        closingCamera=pipeline?.thread;pipeline?.close();pipeline=null;sps=null;pps=null
        server?.let { it.sps=null;it.pps=null };wake?.let { if(it.isHeld) it.release() };wake=null
        stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()
    }
    fun record() {
        val p=pipeline
        if(recordRequested) { recordRequested=false;p?.record(false);recorder.stop();recordOpened=false;return }
        recordingBlockedReason?.let { error=it;return }
        if(p==null)return
        error="";recordRequested=true;recordOpened=false;recordRotation=pose.imageRotation(p.sensorOrientation)
        p.recordFormat?.let { recorder.start(it,recordRotation,p.recordingMode);recordOpened=recorder.accepting }
        p.record(true)
    }
    fun snapshot(captured: ()->Unit={},complete: (String?)->Unit={},night: Boolean=false) {
        val p=pipeline
        if(photoBusy || p==null) { complete(if(photoBusy) "Photo already pending" else "Start camera first");return }
        photoBusy=true
        val acquired={ handler.post { captured() };Unit }
        val finished={ problem: String?->handler.post { photoBusy=false;if(problem!=null)error=problem;complete(problem) };Unit }
        if(night)p.snapshotNight(acquired,finished) else p.snapshot(acquired,finished)
    }
    private fun phoneTelemetry()=JSONObject().apply {
        val p=pipeline
        put("session",phoneSession);put("camera",p?.ready==true && !p.fault && !p.recovering)
        put("recording",recordRequested || recorder.accepting);put("saving",recorder.busy && !recorder.accepting)
        put("elapsed",recorder.durationUs/1_000_000.0);put("free_bytes",freeBytes)
        put("battery",getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
        put("photo_available",p?.photoSize!=null && !photoBusy);put("photo_width",p?.photoSize?.width ?: 0);put("photo_height",p?.photoSize?.height ?: 0)
        put("rotation",pose.imageRotation(p?.sensorOrientation ?: 90));put("error",error.take(160))
        put("thermal_status",thermalStatus)
        put("record_blocked_reason",if(recordRequested) "" else recordingBlockedReason.orEmpty())
        remoteAck?.let { put("ack",it) }
        if(p!=null)put("zoom_control",JSONObject().apply {
            put("min",p.zoomRange.min);put("max",p.zoomRange.max);put("value",p.zoomRatio)
            put("presets",org.json.JSONArray(p.zoomRange.presets))
            put("ack",remoteZoomAck);put("error",remoteZoomError)
        })
    }
    private fun remoteZoom(command: JSONObject) {
        val id=command.optString("id")
        if(!id.matches(Regex("[0-9a-f]{32}")) || id==remoteZoomAck)return
        remoteZoomError=runCatching {
            val p=pipeline ?: error("Start camera first")
            check(p.ready && !p.fault && !p.recovering) { "Camera not ready" }
            p.setZoom(p.zoomRange.remote(command.optDouble("value",Double.NaN)))
        }.exceptionOrNull()?.message.orEmpty().take(160)
        remoteZoomAck=id
    }
    private fun remoteCommand(command: JSONObject) {
        val id=command.optString("id");val action=command.optString("action")
        if(!id.matches(Regex("[0-9a-f]{32}")))return
        val prefs=getSharedPreferences("remoteCommands",0)
        if(prefs.getString("id",null)==id) {
            if(remoteAck?.optString("id")!=id) remoteAck=JSONObject().put("id",id).put("state","failed").put("message","Phone restarted during request; check saved media")
            return
        }
        // Claim durably before executing: a retransmission never repeats a capture.
        if(!prefs.edit().putString("id",id).commit())return
        fun finish(problem: String?,success: String) {
            remoteAck=JSONObject().put("id",id).put("state",if(problem==null) "done" else "failed").put("message",(problem ?: success).take(160))
        }
        remoteAck=JSONObject().put("id",id).put("state","accepted").put("message","Working on phone")
        when(action) {
            "snapshot" -> snapshot({
                remoteAck=JSONObject().put("id",id).put("state","captured").put("message","Photo captured; saving")
            },complete={ finish(it,"Photo saved on phone") })
            "record_start","record_stop" -> {
                val start=action=="record_start"
                if(start && !recordRequested)recordingBlockedReason?.let { finish(it,"");return }
                if(start!=recordRequested)record()
                val deadline=SystemClock.elapsedRealtime()+10000
                val wait=object: Runnable { override fun run() {
                    if(remoteAck?.optString("id")!=id)return
                    if(start && recorder.accepting && recorder.frames>0)finish(null,"Recording on phone")
                    else if(!start && !recorder.busy)finish(null,"Phone recording saved")
                    else if(SystemClock.elapsedRealtime()>deadline || start && !recordRequested)finish(error.ifEmpty { "Recording command did not complete" },"")
                    else handler.postDelayed(this,100)
                } }
                handler.post(wait)
            }
            else -> finish("Unsupported phone command","")
        }
    }
    fun statusJson()=JSONObject().apply {
        put("camera",pipeline?.status ?: "Camera stopped");put("codec",pipeline?.codecName ?: "")
        put("streamQuality",pipeline?.streamQuality?.label ?: "")
        put("streamWidth",pipeline?.streamQuality?.width ?: 0);put("streamHeight",pipeline?.streamQuality?.height ?: 0)
        put("fps",pipeline?.fps ?: 0);put("frames",pipeline?.totalFrames ?: 0);put("frameAgeMs",pipeline?.frameAgeMs ?: -1)
        put("zoom",pipeline?.zoomRatio ?: 1f);put("measuredZoom",pipeline?.measuredZoom ?: 1f)
        put("zoomMin",pipeline?.zoomRange?.min ?: 1f);put("zoomMax",pipeline?.zoomRange?.max ?: 1f);put("activeLens",pipeline?.activeLens ?: "")
        put("recordingMode",pipeline?.recordingMode?.name ?: "")
        put("recordBitrate",pipeline?.let { it.recordingMode.bitrate(it.recordQuality) } ?: 0)
        put("recordPlaybackSeconds",recorder.playbackDurationUs/1_000_000.0)
        put("nightAvailable",pipeline?.nightAvailable ?: false);put("nightCapturing",pipeline?.nightCapturing ?: false)
        put("processing",pipeline?.processingStatus ?: "");put("photoProcessing",pipeline?.photoProcessingStatus ?: "")
        put("processingCapabilities",pipeline?.processingCapabilities ?: "");put("extensions",pipeline?.extensionCapabilities ?: "")
        put("enhancedPhotos",pipeline?.enhancedPhotos ?: false);put("videoDenoise",pipeline?.videoDenoise ?: false)
        put("recordQuality",pipeline?.recordQuality?.label ?: "");put("recordCodec",pipeline?.recordCodecName ?: "")
        put("recordEncoderFrames",pipeline?.recordFrames ?: 0);put("recordEncoderFps",pipeline?.recordFps ?: 0)
        put("recordEncoderActive",pipeline?.recordingActive ?: false);put("previewAttached",pipeline?.previewAttached ?: false)
        put("cameraRecovering",pipeline?.recovering ?: false)
        put("pairingState",companion.state);put("pairingHost",companion.host);put("pairingMessage",companion.message)
        put("phoneControl",phoneTelemetry());put("photoStatus",photos.status)
        put("bleBusy",ble.busy);put("bleMessage",ble.message)
        put("cameraCombinationSupported",pipeline?.supportedCombination ?: "");put("availableRecordings",pipeline?.availableRecordings ?: "")
        put("concurrentWifi",wifi.concurrent);put("localWifi",wifi.status);put("primaryNetwork",wifi.primaryStatus)
        put("address",wifi.localAddress ?: "");put("rtsp",server?.url ?: "");put("viewers",server?.connections ?: 0)
        put("piConnection",wifi.probe);put("networkQueueDrops",server?.dropped?.get() ?: 0)
        put("recording",recorder.accepting);put("recordRequested",recordRequested);put("recordStatus",recorder.status)
        put("recordFrames",recorder.frames);put("recordSeconds",recorder.durationUs/1_000_000.0)
        put("freeBytes",freeBytes);put("thermalStatus",thermalStatus);put("thermalLabel",thermalLabel)
        val battery=registerReceiver(null,IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        put("batteryTemperatureC",(battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,0) ?: 0)/10.0)
        put("batteryPercent",battery?.getIntExtra(BatteryManager.EXTRA_LEVEL,-1) ?: -1)
        put("batteryPlugged",battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED,0) ?: 0)
        put("batteryCurrentUa",getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW))
        put("interactive",getSystemService(PowerManager::class.java).isInteractive)
        put("updatedElapsedMs",SystemClock.elapsedRealtime());put("error",error)
    }
    private val tick=object: Runnable {
        override fun run() {
            connectionOwner.reconcile()
            if(pipeline!=null) {
                wake?.acquire(10*60*1000L)
                if(recordRequested && recordOpened && !recorder.accepting) { recordRequested=false;recordOpened=false;pipeline?.record(false) }
                if(RecordingPolicy.tooHot(thermalStatus) && recordRequested) { record();error="Phone too hot · recording stopped; saving video" }
                if(!RecordingPolicy.tooHot(thermalStatus) && error.startsWith("Phone too hot"))error=""
                val key="$recordRequested:${recorder.durationUs/1_000_000}:${server?.connections}"
                if(key!=lastNotification) { lastNotification=key;getSystemService(NotificationManager::class.java).notify(1,notification()) }
            }
            if(provisioning && pipeline==null && lastNotification!=ble.message) {
                lastNotification=ble.message;getSystemService(NotificationManager::class.java).notify(1,notification())
            }
            runCatching { val tmp=File(filesDir,"status.tmp");tmp.writeText(statusJson().toString(2));tmp.renameTo(File(filesDir,"status.json")) }
            handler.postDelayed(this,1000)
        }
    }
    override fun onDestroy() {
        destroying=true;generation++;handler.removeCallbacks(tick);recordRequested=false
        pipeline?.close();pipeline=null;recorder.close();pose.disable();photos.close();connectionOwner.close()
        networkWorker.execute { server?.close();server=null };networkWorker.shutdown()
        wake?.let { if(it.isHeld) it.release() };super.onDestroy()
    }
}
