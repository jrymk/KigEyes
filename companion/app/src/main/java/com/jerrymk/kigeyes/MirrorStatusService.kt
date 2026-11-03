package com.jerrymk.kigeyes

import android.app.*
import android.content.Intent
import android.content.Context
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.os.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Owns local IPC polling and its optional notification, never the privileged worker. */
class MirrorStatusService: Service() {
    inner class LocalBinder: Binder() { val service get()=this@MirrorStatusService }
    private val executor=Executors.newSingleThreadScheduledExecutor()
    private var client: MirrorWorkerClient?=null
    private val main=Handler(Looper.getMainLooper())
    private val commandPending=AtomicBoolean(false)
    @Volatile var state=MirrorWorkerState(checking=true);private set
    private var foreground=false
    private var destroyed=false
    private var networkOwnerBound=false
    private var cameraOwner: CameraService?=null
    private val networkOwner=object: ServiceConnection {
        override fun onServiceConnected(name: ComponentName,binder: IBinder) { cameraOwner=(binder as CameraService.LocalBinder).service;cameraOwner?.connections?.background=foreground }
        override fun onServiceDisconnected(name: ComponentName) { cameraOwner=null }
    }
    private var notified: MirrorWorkerState?=null
    private val preferences get()=getSharedPreferences("mirrorStatus",0)
    val notificationEnabled get()=preferences.getBoolean("notification",true)
    val busy get()=commandPending.get()

    override fun onCreate() {
        super.onCreate()
        // Retain the existing Wi-Fi owner while status is running, without starting Camera2.
        networkOwnerBound=bindService(Intent(this,CameraService::class.java),networkOwner,Context.BIND_AUTO_CREATE)
        client=runCatching { MirrorWorkerClient() }.getOrNull()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL,"Phone screen",NotificationManager.IMPORTANCE_LOW).apply {
                description="Phone worker and screen sharing status";setSound(null,null);enableVibration(false)
            })
        executor.scheduleWithFixedDelay({ refresh() },0,2,TimeUnit.SECONDS)
    }
    override fun onBind(intent: Intent)=LocalBinder()
    private fun refresh() {
        if(client==null)client=runCatching { MirrorWorkerClient() }.getOrNull()
        var current=runCatching { client?.request() ?: MirrorWorkerState() }.getOrElse { MirrorWorkerState() }
        // Migrated workers may predate opt-in. No persisted app consent means pause,
        // never silently resume when connecting or completing Bluetooth setup.
        if(current.available && current.allowed && !preferences.getBoolean("sharingConsent",false))
            current=runCatching { client?.request("set_allowed",false) ?: current }.getOrDefault(current)
        publish(current)
    }
    private fun publish(value: MirrorWorkerState) {
        state=value
        main.post { if(!destroyed && foreground && notified!=value) {
            getSystemService(NotificationManager::class.java).notify(ID,notification());notified=value
        } }
    }
    fun command(action: String,allowed: Boolean?=null) {
        if(!commandPending.compareAndSet(false,true))return
        executor.execute {
            try {
                val value=client?.request(action,allowed) ?: MirrorWorkerState()
                if(action=="set_allowed" && value.available && value.allowed==allowed)
                    preferences.edit().putBoolean("sharingConsent",allowed==true).apply()
                publish(value)
            } catch(_: Exception) {
                publish(if(action=="screen_off") state.copy(error="Screen-off mode could not start. Use an unlocked phone with a PIN/password and active screen sharing.")
                    else MirrorWorkerState(error="Could not reach the worker. Check its status or install/start it via ADB."))
            } finally { commandPending.set(false) }
        }
    }
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        if(intent?.action=="HIDE") {
            preferences.edit().putBoolean("notification",false).apply()
            foreground=false;cameraOwner?.connections?.background=false;stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();return START_NOT_STICKY
        }
        if(intent==null && !notificationEnabled) { stopSelf();return START_NOT_STICKY }
        preferences.edit().putBoolean("notification",true).apply()
        startForeground(ID,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);foreground=true;cameraOwner?.connections?.background=true
        when(intent?.action) {
            "PAUSE"->command("set_allowed",false)
            "RESUME"->command("set_allowed",true)
            "SCREEN_OFF"->command("screen_off")
        }
        return START_STICKY
    }
    private fun actionIntent(action: String,code: Int)=PendingIntent.getService(this,code,
        Intent(this,MirrorStatusService::class.java).setAction(action),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun notification(): Notification {
        val current=state
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java).putExtra("showWorker",true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder=Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_phone_screen)
            .setContentTitle(current.title).setContentText(current.detail).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false).setCategory(Notification.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .setDeleteIntent(actionIntent("HIDE",3))
        if(current.available && !current.stopping) builder.addAction(Notification.Action.Builder(null,
            if(current.allowed)"Pause" else "Resume",actionIntent(if(current.allowed)"PAUSE" else "RESUME",1)).build())
        if(current.screenOffSupported && current.streaming && current.screenGuard!=1)
            builder.addAction(Notification.Action.Builder(null,"Screen off",actionIntent("SCREEN_OFF",4)).build())
        builder.addAction(Notification.Action.Builder(null,"Stop background connection",actionIntent("HIDE",3)).build())
        if(Build.VERSION.SDK_INT>=36) {
            builder.addExtras(Bundle().apply { putBoolean(Notification.EXTRA_REQUEST_PROMOTED_ONGOING,current.promotable) })
            if(current.promotable) builder.setShortCriticalText("Live")
        }
        return builder.build()
    }
    override fun onDestroy() {
        destroyed=true;client?.close();executor.shutdownNow();main.removeCallbacksAndMessages(null)
        cameraOwner?.connections?.background=false;cameraOwner=null
        if(networkOwnerBound)unbindService(networkOwner)
        networkOwnerBound=false
        super.onDestroy()
    }
    companion object { private const val CHANNEL="phone_worker";private const val ID=203 }
}
