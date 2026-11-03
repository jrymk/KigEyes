package com.jerrymk.kigeyes

import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.FrameLayout
import android.widget.TextView

/** Owns only an optional input overlay; never opens a camera or starts CameraService. */
class TouchShieldService: Service() {
    companion object { var current: TouchShieldService? = null; private set }
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var windows: WindowManager
    private var overlay: FrameLayout? = null
    private var attached = false
    private var active = false
    private val expire = Runnable { release() }
    private val restore = Runnable { if(active) show() }
    private val unlock = Runnable { release() }
    private val screenOff = object: BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { release() }
    }
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate(); current = this
        windows = getSystemService(WindowManager::class.java)
        if(Build.VERSION.SDK_INT>=33) registerReceiver(screenOff,IntentFilter(Intent.ACTION_SCREEN_OFF),RECEIVER_NOT_EXPORTED)
        else registerReceiver(screenOff,IntentFilter(Intent.ACTION_SCREEN_OFF))
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("touch_shield","Phone touch shield",NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this,0,Intent(this,TouchShieldService::class.java).setAction("unlock"),PendingIntent.FLAG_IMMUTABLE)
        val note = Notification.Builder(this,"touch_shield").setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("KigEyes touch shield").setContentText("Hold the shield label to unlock, or use this notification.")
            .setOngoing(true).addAction(Notification.Action.Builder(null,"Unlock touches",stop).build()).build()
        if(Build.VERSION.SDK_INT>=34) startForeground(43,note,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(43,note)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if(intent?.action=="lock" && Settings.canDrawOverlays(this)) {
            active=true; renew(); show()
        } else release()
        return START_NOT_STICKY
    }
    private fun renew() { handler.removeCallbacks(expire); handler.postDelayed(expire,15000) }
    fun command(action: String?): Boolean {
        if(!active) return false
        when(action) {
            "renew" -> renew()
            "permit" -> { hide();handler.removeCallbacks(restore);handler.postDelayed(restore,1000) }
            "restore" -> { handler.removeCallbacks(restore);show() }
            "unlock" -> { release();return false }
            "status" -> Unit
            else -> return false
        }
        return active
    }
    @SuppressLint("ClickableViewAccessibility") // Hold gesture deliberately cannot be triggered by an accidental tap.
    private fun show() {
        if(attached || !active) return
        if(!Settings.canDrawOverlays(this)) { release();return }
        val view = overlay ?: FrameLayout(this).apply {
            isClickable=true
            val badge=TextView(this@TouchShieldService).apply {
                text="Touch shield · hold to unlock";textSize=14f;setTextColor(Color.WHITE);setBackgroundColor(0xdd222222.toInt())
                val pad=(12*resources.displayMetrics.density).toInt();setPadding(pad,pad,pad,pad)
                setOnTouchListener { _, event ->
                    when(event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> handler.postDelayed(unlock,1800)
                        MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(unlock)
                    };true
                }
            }
            addView(badge,FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        }.also { overlay=it }
        val params=WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT)
        try { windows.addView(view,params);attached=true } catch(_: RuntimeException) { release() }
    }
    private fun hide() { if(attached) { overlay?.let { windows.removeView(it) };attached=false };handler.removeCallbacks(unlock) }
    private fun release() { active=false;handler.removeCallbacksAndMessages(null);hide();stopSelf() }
    override fun onDestroy() {
        active=false;handler.removeCallbacksAndMessages(null);hide();unregisterReceiver(screenOff)
        if(current===this) current=null
        super.onDestroy()
    }
}

/** Explicit, shell-permission-protected commands; renewal never creates a shield. */
class TouchShieldReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        resultCode=if(TouchShieldService.current?.command(intent.getStringExtra("command"))==true) 1 else 0
    }
}
