package com.jerrymk.kigeyes

import android.content.res.Configuration
import android.graphics.Color
import android.os.*
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.DynamicColors

/** Full-page viewer, with overlay controls and a player retained through rotation. */
@androidx.annotation.OptIn(UnstableApi::class)
class WearerViewerActivity: AppCompatActivity() {
    private lateinit var session: WearerSession
    private lateinit var video: PlayerView
    private lateinit var toolbar: MaterialToolbar
    private lateinit var badge: MaterialCardView
    private lateinit var status: TextView
    private lateinit var retry: MaterialButton
    private val handler=Handler(Looper.getMainLooper())
    private var controlsUntil=0L
    private val landscape get()=resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE
    private fun dp(n: Int)=(n*resources.displayMetrics.density).toInt()
    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved);DynamicColors.applyToActivityIfAvailable(this)
        WindowCompat.setDecorFitsSystemWindows(window,false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        session=ViewModelProvider(this)[WearerSession::class.java]
        if(BuildConfig.DEBUG)intent.getStringExtra("probeStream")?.let { session.configureProbe(android.net.Uri.parse(it)) }
        video=PlayerView(this).apply { useController=false;setBackgroundColor(Color.BLACK) }
        toolbar=MaterialToolbar(this).apply {
            title="Wearer view";setTitleTextColor(Color.WHITE);setSubtitleTextColor(Color.LTGRAY)
            setBackgroundColor(0x99000000.toInt());setNavigationIcon(R.drawable.ic_home_arrow_left);setNavigationIconTint(Color.WHITE)
            navigationContentDescription="Close wearer view";setNavigationOnClickListener { finish() }
        }
        status=TextView(this).apply { text="Connecting…";textSize=14f;setTextColor(Color.WHITE);setPadding(dp(16),dp(10),dp(16),dp(10)) }
        retry=MaterialButton(this).apply { text="Retry";visibility=View.GONE;setOnClickListener { session.retry();controlsUntil=SystemClock.elapsedRealtime()+3000 } }
        badge=MaterialCardView(this).apply {
            radius=dp(20).toFloat();cardElevation=0f;setCardBackgroundColor(0xcc202124.toInt())
            addView(LinearLayout(this@WearerViewerActivity).apply { gravity=Gravity.CENTER_VERTICAL;addView(status);addView(retry) })
        }
        val root=FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK);addView(video,FrameLayout.LayoutParams(-1,-1))
            addView(toolbar,FrameLayout.LayoutParams(-1,dp(64),Gravity.TOP))
            addView(badge,FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin=dp(20);leftMargin=dp(20);rightMargin=dp(20) })
            setOnClickListener { controlsUntil=if(SystemClock.elapsedRealtime()<controlsUntil)0 else SystemClock.elapsedRealtime()+3000 }
        }
        video.setOnClickListener { controlsUntil=if(SystemClock.elapsedRealtime()<controlsUntil)0 else SystemClock.elapsedRealtime()+3000 }
        setContentView(root)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { view,insets->
            val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left,if(landscape)0 else bars.top,bars.right,if(landscape)0 else bars.bottom);insets
        }
        WindowCompat.getInsetsController(window,root).apply {
            isAppearanceLightStatusBars=false;isAppearanceLightNavigationBars=false
            systemBarsBehavior=WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if(landscape)hide(WindowInsetsCompat.Type.systemBars()) else show(WindowInsetsCompat.Type.systemBars())
        }
        controlsUntil=SystemClock.elapsedRealtime()+3000
    }
    override fun onStart() { super.onStart();session.start();handler.post(refresh) }
    override fun onStop() {
        handler.removeCallbacks(refresh);video.player=null
        if(!isChangingConfigurations)session.stop()
        super.onStop()
    }
    private val refresh=object: Runnable {
        override fun run() {
            session.update();val player=session.player
            if(video.player!==player)video.player=player
            toolbar.subtitle=session.name
            val failed=player?.playerError!=null || player?.playbackState==Player.STATE_ENDED
            val live=player?.playbackState==Player.STATE_READY
            status.text=when { failed->"Stream unavailable";live->"● Live";else->"Connecting…" }
            retry.visibility=if(failed)View.VISIBLE else View.GONE
            val show=!landscape || !live || SystemClock.elapsedRealtime()<controlsUntil
            toolbar.visibility=if(show)View.VISIBLE else View.GONE;badge.visibility=if(show)View.VISIBLE else View.GONE
            handler.postDelayed(this,250)
        }
    }
}
