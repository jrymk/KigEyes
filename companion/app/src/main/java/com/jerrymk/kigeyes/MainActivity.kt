package com.jerrymk.kigeyes

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.os.*
import android.view.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar

class MainActivity: AppCompatActivity(),TextureView.SurfaceTextureListener {
    private var service: CameraService?=null
    private var bound=false
    private val handler=Handler(Looper.getMainLooper())
    private lateinit var screen: CameraScreen
    private lateinit var home: CompanionHome
    private lateinit var host: android.widget.FrameLayout
    private var cameraPage=false
    private var previewTarget: PreviewTarget?=null
    private var attached: CameraPipeline?=null
    private var selected=CaptureQuality.FULL_HD
    private var pending: (() -> Unit)?=null
    private var required=arrayOf<String>()
    private var pendingTest: String?=null
    private lateinit var settings: SettingsPage
    private var settingsPage=false
    private var visible=false
    private var connectionSetupOpen=false
    private var connectDialog: ConnectionDialog?=null
    private var wasConnecting=false
    private var workerSetup: SetupSheet?=null
    private var workerSetupOpen=false
    private var installMessage=""
    private var screenOffSheet: SetupSheet?=null
    private var mirrorService: MirrorStatusService?=null
    private var mirrorBound=false
    private val mirrorConnection=object: ServiceConnection {
        override fun onServiceConnected(name: ComponentName,binder: IBinder) {
            mirrorService=(binder as MirrorStatusService.LocalBinder).service
            if(visible && hotspot().second.isNotEmpty() && mirrorService?.notificationEnabled==true)startForegroundService(Intent(this@MainActivity,MirrorStatusService::class.java).setAction("SHOW"))
        }
        override fun onServiceDisconnected(name: ComponentName) { mirrorService=null }
    }
    private fun showWearerViewer()=safely {
        val owner=service ?: return@safely
        val network=owner.wifi.network
        val host=owner.companion.host
        if(network==null || host.isEmpty()) { connectKigEyes();return@safely }
        startActivity(Intent(this,WearerViewerActivity::class.java))
    }
    private fun showMirror() {
        workerSetupOpen=true;connectionSetupOpen=false
        connectDialog?.dismiss();connectDialog=null
        renderWorkerSetup()
    }
    private fun closeWorkerSetup() {
        workerSetupOpen=false;WorkerInstallRequest.clear();workerSetup?.dismiss();workerSetup=null
    }
    private fun workerSetupAction() {
        val current=mirrorService?.state ?: return
        when {
            !current.available || current.stopping || !current.screenOffSupported->{
                WorkerInstallRequest.begin();installMessage="Connect this phone to the USB port on KigEyes, then approve USB debugging on this phone."
            }
            service?.wifi?.localAddress==null->connectKigEyes()
            else->{ withBackgroundConnection { mirrorService?.command("set_allowed",true) };closeWorkerSetup() }
        }
    }
    private fun renderWorkerSetup() {
        if(!workerSetupOpen || !visible)return
        if(workerSetup==null)workerSetup=SetupSheet(this,::workerSetupAction,::closeWorkerSetup,{
            safely { startActivity(Intent(if(android.provider.Settings.Global.getInt(contentResolver,
                android.provider.Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,0)==1)
                android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS else android.provider.Settings.ACTION_DEVICE_INFO_SETTINGS)) }
        }).also { it.show() }
        val current=mirrorService?.state ?: MirrorWorkerState(checking=true)
        val pendingInstall=WorkerInstallRequest.current().isNotEmpty()
        val developerEnabled=android.provider.Settings.Global.getInt(contentResolver,android.provider.Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,0)==1
        when {
            current.checking->workerSetup?.render("Screen sharing","Checking this phone…","Checking…",true,false)
            !current.available || current.stopping || !current.screenOffSupported->{
                workerSetup?.render("Set up screen sharing",if(pendingInstall)WorkerInstallRequest.message.ifEmpty { installMessage.ifEmpty { "Connect this phone to KigEyes by USB and approve debugging." } } else if(!developerEnabled)
                    "If USB debugging is not enabled, open About phone and tap Build number seven times, then enable USB debugging in Developer options.\n\nPlug this phone into KigEyes and approve debugging. If debugging is already enabled, continue below." else
                    "1. Enable USB debugging in Developer options.\n2. Plug this phone into KigEyes.\n3. Approve debugging on this phone.\n\nInstallation stays ready until the phone restarts. Sharing starts only when you choose it.",
                    if(pendingInstall)"Waiting for USB…" else "Ready — install on this phone",pendingInstall,!pendingInstall,if(developerEnabled)"Open Developer options" else "Open About phone")

            }
            service?.wifi?.localAddress==null->workerSetup?.render("Worker installed","Sharing is off. Connect to KigEyes to continue.","Connect to KigEyes")
            else->{
                WorkerInstallRequest.clear()
                workerSetup?.render("Ready to share", "Connected to ${service?.companion?.deviceName ?: "KigEyes"}.","Start sharing",completed=true)
            }
        }
    }
    fun workerNotification(enabled: Boolean) {
        if(enabled) {
            val items=if(Build.VERSION.SDK_INT>=33)listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
            permissions(items) { startForegroundService(Intent(this,MirrorStatusService::class.java).setAction("SHOW")) }
        } else startService(Intent(this,MirrorStatusService::class.java).setAction("HIDE"))
    }

    private val connection=object: ServiceConnection {
        override fun onServiceConnected(name: ComponentName,binder: IBinder) {
            service=(binder as CameraService.LocalBinder).service
            service?.connections?.visible=visible
            pendingTest?.let { pendingTest=null;testAction(it) }

        }
        override fun onServiceDisconnected(name: ComponentName) { service=null;attached=null }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state);DynamicColors.applyToActivityIfAvailable(this)
        WindowCompat.setDecorFitsSystemWindows(window,false)
        WindowCompat.getInsetsController(window,window.decorView).apply {
            isAppearanceLightStatusBars=false;isAppearanceLightNavigationBars=false
            show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        selected=CaptureQuality.recording(getPreferences(0).getString("recordQuality",null))
        screen=CameraScreen(this,selected,
            { safely { if(service?.pipeline!=null) { service?.stopCamera();attached=null } else cameraPermission { startCamera() } } },
            { safely { service?.record() } },::showConnection,
            { selected=it;getPreferences(0).edit().putString("recordQuality",it.name).apply() },::diagnostics,
            { safely { service?.snapshot({ screen.photoCaptured() },{ problem -> screen.photoFinished(problem) }) } },
            { service?.pipeline?.setZoom(it) },{ photos,video->service?.processing(photos,video) },
            { safely { service?.snapshot({ screen.photoCaptured() },{ problem->screen.photoFinished(problem) },night=true) } },::showHome,::showHome)
        screen.preview.surfaceTextureListener=this
        home=CompanionHome(this,::connectKigEyes,{showSettings()},::shareAction,::showCamera,::showWearerViewer)
        settings=SettingsPage(this,::showHome,::settingsAction,::settingsToggle,::selectSlot)
        host=android.widget.FrameLayout(this).apply {
            addView(home.root,android.widget.FrameLayout.LayoutParams(-1,-1))
            addView(screen.root,android.widget.FrameLayout.LayoutParams(-1,-1))
            addView(settings.root,android.widget.FrameLayout.LayoutParams(-1,-1))
        }
        setContentView(host)
        connectionSetupOpen=state?.getBoolean("connectionSetup",false) ?: false
        workerSetupOpen=state?.getBoolean("workerSetup",false) ?: false
        cameraPage=state?.getBoolean("cameraPage",false) ?: false
        if(state?.getBoolean("settingsPage")==true)showSettings() else if(cameraPage)showCamera() else showHome()
        onBackPressedDispatcher.addCallback(this,object: androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if(cameraPage || settingsPage)showHome() else { isEnabled=false;onBackPressedDispatcher.onBackPressed();isEnabled=true }
            }
        })
        if(BuildConfig.DEBUG) pendingTest=DevelopmentActions.take()
    }
    private fun safely(action: ()->Unit) { try { action() } catch(e: Exception) { Snackbar.make(host,e.message ?: "Could not complete action",Snackbar.LENGTH_LONG).show() } }
    private fun permissions(items: List<String>,action: ()->Unit) {
        if(pending!=null) {
            Snackbar.make(host,"Finish the current permission request first.",Snackbar.LENGTH_SHORT).show()
            return
        }
        required=items.toTypedArray()
        if(required.any { checkSelfPermission(it)!=PackageManager.PERMISSION_GRANTED }) { pending=action;requestPermissions(required,10) }
        else safely(action)
    }
    private fun cameraPermission(action: ()->Unit) {
        val items=mutableListOf(Manifest.permission.CAMERA)
        if(Build.VERSION.SDK_INT>=33 && !getPreferences(0).getBoolean("notificationAsked",false)) {
            items.add(Manifest.permission.POST_NOTIFICATIONS);getPreferences(0).edit().putBoolean("notificationAsked",true).apply()
        }
        permissions(items,action)
    }
    fun wifiPermission(action: ()->Unit) {
        val items=mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION)
        if(Build.VERSION.SDK_INT>=33) items.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        permissions(items,action)
    }
    private fun withBackgroundConnection(action: ()->Unit) {
        if(!getSharedPreferences("mirrorStatus",0).getBoolean("notification",true)) { action();return }
        val items=if(Build.VERSION.SDK_INT>=33)listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
        permissions(items) {
            if(getSharedPreferences("mirrorStatus",0).getBoolean("notification",true))
                startForegroundService(Intent(this,MirrorStatusService::class.java).setAction("SHOW"))
            action()
        }
    }
    private fun showConnectDialog() {
        connectionSetupOpen=true
        if(connectDialog?.isShowing==true)return
        connectDialog=ConnectionDialog(this,::connectKigEyes,{service?.disconnectConnection()},::showSettings,{connectionSetupOpen=false}).also { it.show() }
        renderConnectionDialog()
    }
    private fun connectKigEyes() {
        if(service==null)return
        showConnectDialog()
        if(hotspot().second.isEmpty()) { startBluetoothSetup();return }
        withBackgroundConnection { wifiPermission { service?.connections?.connect() } }
    }
    private fun renderConnectionDialog() {
        val owner=service ?: return
        if(connectionSetupOpen && connectDialog==null)showConnectDialog()
        val busy=owner.ble.busy || owner.wifi.phase==WifiPhase.CONNECTING
        val connected=owner.wifi.phase==WifiPhase.CONNECTED || mirrorService?.state?.connected==true
        if(connected && !busy) {
            if(workerSetupOpen) { connectionSetupOpen=false;connectDialog?.dismiss();connectDialog=null }
            else connectDialog?.render(false,"Connected to ${owner.companion.deviceName}",complete=true)
        }
        else {
            // Automatic requests use the same task dialog; never interrupt the viewfinder or saving.
            if(busy && !wasConnecting && !cameraPage && !settingsPage && !workerSetupOpen)showConnectDialog()
            val detail=when {
                owner.ble.busy || owner.ble.failed->owner.ble.message
                owner.wifi.phase==WifiPhase.CONNECTING->"Approve Android’s Wi-Fi request if prompted. Your saved KigEyes password is supplied automatically."
                owner.wifi.phase==WifiPhase.FAILED->owner.wifi.status
                hotspot().second.isEmpty()->owner.ble.message
                else->if(owner.wifi.concurrent)"Join KigEyes directly to share your screen. Your normal Wi-Fi can stay connected." else "Join KigEyes using the saved password. Your current Wi-Fi connection will be replaced."
            }
            connectDialog?.render(busy,detail,owner.wifi.phase==WifiPhase.FAILED || owner.ble.failed)
        }
        wasConnecting=busy
    }
    private fun shareAction(action: HomeShareAction) {
        when(action) {
            HomeShareAction.SETUP->showMirror()
            HomeShareAction.ALLOW->if(mirrorService?.state?.available!=true || service?.wifi?.localAddress==null)showMirror() else withBackgroundConnection { mirrorService?.command("set_allowed",true) }
            HomeShareAction.PAUSE->mirrorService?.command("set_allowed",false)
        }
    }
    private fun showSettings() {
        connectionSetupOpen=false
        showHome();settingsPage=true;home.root.visibility=View.GONE;settings.root.visibility=View.VISIBLE
        connectDialog?.dismiss();connectDialog=null
        renderSettings()
    }
    private fun settingsAction(action: SettingsAction)=safely {
        when(action) {
            SettingsAction.INSTALL_WORKER->showMirror()
            SettingsAction.SCREEN_OFF->{
                if(mirrorService?.state?.screenOffSupported!=true) { showMirror();return@safely }
                screenOffSheet?.dismiss()
                screenOffSheet=SetupSheet(this,{
                    screenOffSheet?.dismiss();screenOffSheet=null
                    mirrorService?.command("screen_off");moveTaskToBack(true)
                },{screenOffSheet=null}).also {
                    it.render("Screen off while shooting", "Keeps the mirrored camera running with the panel dark. Press Power to lock Android and restore the screen.\n\nRequires a PIN/password. This temporarily makes screen-off lock immediately; your normal lock delay is restored when finished. Locking may stop a stock-camera recording. Use the notification’s Screen off action while in your camera.","Turn screen off")
                    it.show()
                }
            }
            SettingsAction.CONNECT->connectKigEyes()
            SettingsAction.DISCONNECT->service?.disconnectConnection()
            SettingsAction.PAIR->{ showConnectDialog();startBluetoothSetup() }
            SettingsAction.CURRENT_WIFI->wifiPermission { service?.connections?.useCurrentWifi() }
            SettingsAction.ADDRESS->{
                val field=com.google.android.material.textfield.TextInputEditText(this).apply { setSingleLine();hint="KigEyes IP address";inputType=android.text.InputType.TYPE_CLASS_PHONE }
                MaterialAlertDialogBuilder(this).setTitle("KigEyes address").setView(field)
                    .setPositiveButton("Find") { _,_->service?.companion?.discover(field.text.toString().trim()) }.setNegativeButton("Cancel",null).show()
            }
            SettingsAction.STOP_WORKER->MaterialAlertDialogBuilder(this).setTitle("Stop the phone worker?")
                .setMessage("Starting it again requires authorized ADB from KigEyes. Pause screen sharing to resume later without ADB.")
                .setNegativeButton("Cancel",null).setPositiveButton("Stop worker") { _,_->mirrorService?.command("stop_worker") }.show()
            SettingsAction.PAIR_CAMERA->service?.companion?.let { it.request(it.preferredSlot) }
            SettingsAction.DIAGNOSTICS->diagnostics()
        }
    }
    private fun settingsToggle(key: SettingsToggle,value: Boolean)=safely {
        when(key) {
            SettingsToggle.BACKGROUND->workerNotification(value)
            SettingsToggle.SHARING->shareAction(if(value)HomeShareAction.ALLOW else HomeShareAction.PAUSE)
            SettingsToggle.AUTO_CONNECT->{
                if(value)bluetoothPermissions { service?.connections?.autoConnect=true }
                else service?.connections?.autoConnect=false
            }
        }
    }
    private fun selectSlot(slot: Int) {
        service?.companion?.selectSlot(slot)
        service?.companion?.let { if(it.state=="found" || (it.state=="paired" && it.slot!=slot))it.request(slot) }
    }
    private fun renderSettings() {
        val owner=service;val link=owner?.companion
        settings.render(SettingsState(
            connection=when { owner==null->"Preparing connection…";owner.ble.busy || owner.ble.failed->owner.ble.message;else->owner.wifi.status },
            connectionBusy=owner?.ble?.busy==true || owner?.wifi?.phase==WifiPhase.CONNECTING,
            connected=owner?.wifi?.localAddress!=null,autoConnect=owner?.connections?.autoConnect ?: true,
            concurrent=owner?.wifi?.concurrent==true,autoDetail=owner?.connections?.autoStatus ?: "",
            background=mirrorService?.notificationEnabled ?: getSharedPreferences("mirrorStatus",0).getBoolean("notification",true),
            worker=mirrorService?.state ?: MirrorWorkerState(checking=true),workerBusy=mirrorService?.busy==true,
            slot=link?.preferredSlot ?: 1,pairing=link?.message ?: "",code=link?.code ?: "",
            canPairCamera=link?.state=="found",
            slotEnabled=owner!=null && !owner.ble.busy && link?.state !in listOf("waiting","requesting"),ready=owner!=null))
    }
    private fun showHome() {
        requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        cameraPage=false;settingsPage=false;settings.root.visibility=View.GONE;service?.preview(null);attached=null
        if(::host.isInitialized) { screen.root.visibility=View.GONE;home.root.visibility=View.VISIBLE }
        val dark=resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK==android.content.res.Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window,window.decorView).apply { isAppearanceLightStatusBars=!dark;isAppearanceLightNavigationBars=!dark }
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    private fun showCamera() {
        requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        cameraPage=true;settingsPage=false;settings.root.visibility=View.GONE
        if(::host.isInitialized) { home.root.visibility=View.GONE;screen.root.visibility=View.VISIBLE }
        WindowCompat.getInsetsController(window,window.decorView).apply { isAppearanceLightStatusBars=false;isAppearanceLightNavigationBars=false }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    override fun onSaveInstanceState(state: Bundle) { state.putBoolean("connectionSetup",connectionSetupOpen);state.putBoolean("workerSetup",workerSetupOpen);state.putBoolean("cameraPage",cameraPage);state.putBoolean("settingsPage",settingsPage);super.onSaveInstanceState(state) }
    private fun bluetoothPermissions(action: ()->Unit) {
        val items=mutableListOf(Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION)
        if(Build.VERSION.SDK_INT>=33) items.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        permissions(items,action)
    }
    fun startBluetoothSetup() = withBackgroundConnection { bluetoothPermissions { startForegroundService(Intent(this,CameraService::class.java).setAction("PROVISION")) } }
    override fun onRequestPermissionsResult(code: Int,permissions: Array<out String>,results: IntArray) {
        super.onRequestPermissionsResult(code,permissions,results)
        if(code!=10)return
        val action=pending;pending=null
        if(required.filter { it!=Manifest.permission.POST_NOTIFICATIONS }.all { checkSelfPermission(it)==PackageManager.PERMISSION_GRANTED }) safely { action?.invoke() }
        else Snackbar.make(host,"Permission needed for this action. You can try again from setup.",Snackbar.LENGTH_LONG).show()
    }
    private fun startCamera() { startForegroundService(Intent(this,CameraService::class.java).putExtra("recordQuality",selected.name)) }
    private val hotspotStore by lazy { HotspotStore(this) }
    fun hotspot(): Pair<String,String> = hotspotStore.read()
    private fun showConnection()=showSettings()
    private fun diagnostics() {
        val s=service ?: return
        val text=listOf(s.pipeline?.status ?: "Camera stopped","Stream encoder: ${s.pipeline?.codecName ?: "—"}",
            "Zoom: ${s.pipeline?.zoomRatio ?: 1f}× · lens ${s.pipeline?.activeLens ?: "—"}",
            "Processing: ${s.pipeline?.processingStatus}\nPhoto: ${s.pipeline?.photoProcessingStatus}\n${s.pipeline?.processingCapabilities}\nSupported photo extensions: ${s.pipeline?.extensionCapabilities}",
            "WiFi STA concurrency: ${s.wifi.concurrent}",s.wifi.primaryStatus,s.wifi.status,s.companion.message,s.error).joinToString("\n\n")
        MaterialAlertDialogBuilder(this).setTitle("Diagnostics").setMessage(text).setPositiveButton("Done",null)
            .setNeutralButton("Copy stream URL") { _,_->
                s.server?.url?.let { getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Private camera URL",it)) }
            }.show()
    }
    override fun onStart() {
        super.onStart();visible=true;wasConnecting=false;service?.connections?.visible=true
        if(!mirrorBound)mirrorBound=bindService(Intent(this,MirrorStatusService::class.java),mirrorConnection,Context.BIND_AUTO_CREATE)
        if(intent.getBooleanExtra("showWorker",false)) { intent.removeExtra("showWorker");showHome() }
        if(!bound) bound=bindService(Intent(this,CameraService::class.java),connection,Context.BIND_AUTO_CREATE)
        handler.post(refresh)
    }
    override fun onStop() {
        screenOffSheet?.dismiss();screenOffSheet=null
        visible=false;service?.connections?.visible=false
        workerSetup?.dismiss();workerSetup=null
        connectDialog?.dismiss();connectDialog=null;handler.removeCallbacks(refresh)
        service?.preview(null);attached=null
        if(bound)unbindService(connection)
        bound=false;service=null
        if(mirrorBound)unbindService(mirrorConnection)
        mirrorBound=false;mirrorService=null
        super.onStop()
    }
    override fun onDestroy() {if(bound) unbindService(connection);bound=false;service=null;super.onDestroy() }
    private val refresh=object: Runnable {
        override fun run() {
            previewTarget?.displayDegrees=displayDegrees()
            home.render(companionHomeState(service,mirrorService,hotspot().second.isNotEmpty()))
            if(settingsPage)renderSettings()
            renderConnectionDialog()
            renderWorkerSetup()
            service?.let { s ->
                if(cameraPage && s.pipeline?.ready==true && s.pipeline!=attached) { attached=s.pipeline;s.preview(previewTarget) }
                screen.render(s)
            }
            handler.postDelayed(this,500)
        }
    }
    override fun onSurfaceTextureAvailable(texture: SurfaceTexture,w: Int,h: Int) {
        previewTarget=PreviewTarget(texture,displayDegrees());if(cameraPage)service?.preview(previewTarget)
    }
    private fun displayDegrees()=(screen.preview.display?.rotation ?: Surface.ROTATION_0)*90
    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture,w: Int,h: Int) { previewTarget?.displayDegrees=displayDegrees() }
    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        service?.preview(null);previewTarget?.release();previewTarget=null;return false
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if(intent.getBooleanExtra("showWorker",false)) { intent.removeExtra("showWorker");showHome() }
        if(BuildConfig.DEBUG) DevelopmentActions.take()?.let {
            if(service!=null) testAction(it) else pendingTest=it
        }
    }
    private fun testAction(action: String) { if(!BuildConfig.DEBUG) return;safely {
        when(action) {
            "worker" -> showMirror()
            "home" -> showHome()
            "camera" -> showCamera()
            "uiPortrait" -> requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "uiLandscape" -> requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            "uiRotate" -> requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            "pinchOut" -> testPinch(true)
            "pinchIn" -> testPinch(false)
            "start720","start1080" -> { selected=CaptureQuality.FULL_HD;cameraPermission { startCamera() } }
            "start4k" -> { selected=CaptureQuality.UHD;cameraPermission { startCamera() } }
            "record" -> service?.record()
            "photosDefault" -> service?.processing(false,service?.pipeline?.videoDenoise ?: false)
            "photosEnhanced" -> service?.processing(true,service?.pipeline?.videoDenoise ?: false)
            "videoDenoise" -> service?.processing(true,true)
            "videoDefault" -> service?.processing(true,false)
            "startBalanced","startSmall","startLowFps","startTimelapse" -> {
                selected=CaptureQuality.FULL_HD
                val mode=when(action) { "startBalanced"->RecordingMode.BALANCED;"startSmall"->RecordingMode.SMALL;"startLowFps"->RecordingMode.LOW_FPS;else->RecordingMode.TIMELAPSE }
                cameraPermission { startForegroundService(Intent(this,CameraService::class.java).putExtra("recordQuality",selected.name).putExtra("recordingMode",mode.name)) }
            }
            "photo" -> service?.snapshot()
            "photoNight" -> service?.snapshot(night=true)
            "wifi" -> connectKigEyes()
            "lan" -> wifiPermission { service?.connections?.useCurrentWifi() }
            "disconnect" -> service?.disconnectConnection()
            "stop" -> { service?.stopCamera();attached=null }
            "setup" -> showConnection()
            "pair" -> service?.companion?.request(1)
            "ble" -> startBluetoothSetup()
        }
    } }
    /** Debug-only two-finger input exercises the real preview gesture path. */
    private fun testPinch(outward: Boolean) {
        if(!BuildConfig.DEBUG)return
        val preview=screen.preview;val down=SystemClock.uptimeMillis()
        val span=minOf(preview.width,preview.height)*.45f
        val props=Array(2) { i -> MotionEvent.PointerProperties().apply { id=i;toolType=MotionEvent.TOOL_TYPE_FINGER } }
        fun send(action: Int,count: Int,gap: Float) {
            val coords=Array(count) { i -> MotionEvent.PointerCoords().apply {
                x=preview.width/2f+if(i==0)-gap/2 else gap/2;y=preview.height/2f;pressure=1f;size=1f
            } }
            val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,count,props,coords,0,0,1f,1f,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0)
            preview.dispatchTouchEvent(event);event.recycle()
        }
        val start=if(outward)span else span*2
        send(MotionEvent.ACTION_DOWN,1,start)
        send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),2,start)
        for(step in 1..20)handler.postDelayed({
            val gap=if(outward)span*(1+step/20f) else span*(2-step/20f)
            send(MotionEvent.ACTION_MOVE,2,gap)
            if(step==20) { send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),2,gap);send(MotionEvent.ACTION_UP,1,gap) }
        },step*20L)
    }
}
