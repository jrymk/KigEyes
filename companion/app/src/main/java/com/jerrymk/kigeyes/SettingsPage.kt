package com.jerrymk.kigeyes

import android.content.Context
import android.view.*
import android.widget.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch

internal enum class SettingsAction { CONNECT, DISCONNECT, PAIR, INSTALL_WORKER, CURRENT_WIFI, ADDRESS, STOP_WORKER, SCREEN_OFF, PAIR_CAMERA, DIAGNOSTICS }
internal enum class SettingsToggle { AUTO_CONNECT, BACKGROUND, SHARING }
internal data class SettingsState(
    val connection: String="Preparing connection…",val connectionBusy: Boolean=false,
    val connected: Boolean=false,val autoConnect: Boolean=true,val concurrent: Boolean=true,
    val autoDetail: String="",val background: Boolean=true,
    val worker: MirrorWorkerState=MirrorWorkerState(checking=true),val workerBusy: Boolean=false,
    val slot: Int=1,val pairing: String="Connect to KigEyes to assign a camera.",val code: String="",
    val slotEnabled: Boolean=false,val canPairCamera: Boolean=false,val ready: Boolean=false
)

/** A single settings surface. No services, polling, permissions or networking live in the view. */
internal class SettingsPage(private val context: Context,onBack: ()->Unit,
    private val onAction: (SettingsAction)->Unit,private val onToggle: (SettingsToggle,Boolean)->Unit,onSlot: (Int)->Unit) {
    private fun dp(n: Int)=(n*context.resources.displayMetrics.density).toInt()
    private fun color(attr: Int)=MaterialColors.getColor(context,attr,"Settings")
    private val ink=color(com.google.android.material.R.attr.colorOnSurface)
    private val muted=color(com.google.android.material.R.attr.colorOnSurfaceVariant)
    val root=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(color(com.google.android.material.R.attr.colorSurface)) }
    private var rendering=false
    private fun text(value: String,size: Int=14)=TextView(context).apply {
        text=value;textSize=size.toFloat();setTextColor(if(size>=18)ink else muted)
        setPadding(0,dp(4),0,dp(8))
    }
    private fun button(value: String,action: SettingsAction)=MaterialButton(context,null,
        com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
        text=value;isAllCaps=false;minHeight=dp(48);setOnClickListener { onAction(action) }
    }
    private fun toggle(label: String,key: SettingsToggle)=MaterialSwitch(context).apply {
        text=label;textSize=16f;minHeight=dp(56);setTextColor(ink)
        setOnCheckedChangeListener { _,value->if(!rendering)onToggle(key,value) }
    }
    private val network=text("")
    private val autoDetail=text("")
    private val capability=text("")
    private val connect=button("Connect to KigEyes",SettingsAction.CONNECT)
    private val disconnect=button("Disconnect",SettingsAction.DISCONNECT)
    private val pair=button("Pair nearby KigEyes",SettingsAction.PAIR)
    private val auto=toggle("Connect automatically",SettingsToggle.AUTO_CONNECT)
    private val background=toggle("Stay connected in background",SettingsToggle.BACKGROUND)
    private val sharing=toggle("Allow screen sharing",SettingsToggle.SHARING)
    private val worker=text("")
    private val screenOff=button("Screen off while shooting…",SettingsAction.SCREEN_OFF)
    private val stop=button("Stop worker…",SettingsAction.STOP_WORKER)
    private val pairCamera=button("Assign this camera",SettingsAction.PAIR_CAMERA)
    private val pairing=text("")
    private val code=text("",24)
    private val slots=MaterialButtonToggleGroup(context).apply { isSingleSelection=true;isSelectionRequired=true }
    private val one=View.generateViewId()
    private val two=View.generateViewId()

    init {
        root.addView(com.google.android.material.appbar.MaterialToolbar(context).apply {
            title="Settings";setTitleTextColor(ink)
            setNavigationIcon(R.drawable.ic_home_arrow_left);setNavigationIconTint(ink)
            navigationContentDescription="Back";setNavigationOnClickListener { onBack() }
        },LinearLayout.LayoutParams(-1,dp(64)))
        val scroll=ScrollView(context).apply { isFillViewport=true }
        val center=FrameLayout(context)
        val body=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(8),dp(20),dp(24)) }
        center.addView(body,FrameLayout.LayoutParams(-1,-2,Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        center.addOnLayoutChangeListener { _,l,_,r,_,_,_,_,_->
            val width=minOf(r-l,dp(720));if(body.layoutParams.width!=width)body.layoutParams=body.layoutParams.apply { this.width=width }
        }
        scroll.addView(center);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        fun section(label: String,vararg views: View) {
            val content=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(16),dp(20),dp(16)) }
            content.addView(text(label,20))
            views.forEach { content.addView(it,LinearLayout.LayoutParams(-1,-2)) }
            body.addView(MaterialCardView(context).apply {
                radius=dp(24).toFloat();cardElevation=0f;strokeWidth=0
                setCardBackgroundColor(color(com.google.android.material.R.attr.colorSurfaceContainer));addView(content)
            },LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(16) })
        }
        val networkActions=LinearLayout(context)
        networkActions.addView(connect,LinearLayout.LayoutParams(0,-2,1f))
        networkActions.addView(disconnect,LinearLayout.LayoutParams(0,-2,1f).apply { leftMargin=dp(8) })
        section("Connection",network,networkActions,pair,capability,auto,autoDetail,background,
            text("Keep the connection while the app is in the background."))
        section("Screen sharing",worker,sharing,button("Screen sharing setup",SettingsAction.INSTALL_WORKER),screenOff,stop)
        for((id,label) in listOf(one to "Camera 1",two to "Camera 2"))slots.addView(MaterialButton(context,null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { this.id=id;text=label;isCheckable=true },LinearLayout.LayoutParams(0,dp(52),1f))
        slots.addOnButtonCheckedListener { _,id,checked->if(checked && !rendering)onSlot(if(id==one)1 else 2) }
        section("Phone camera",text("Choose the camera slot for this phone. Changing an occupied slot requires approval in the glasses."),slots,pairCamera,pairing,code)
        section("Advanced",button("Use current Wi-Fi",SettingsAction.CURRENT_WIFI),
            button("Enter KigEyes address",SettingsAction.ADDRESS),button("Diagnostics",SettingsAction.DIAGNOSTICS))
        ViewCompat.setOnApplyWindowInsetsListener(root) { view,insets->
            val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left,bars.top,bars.right,bars.bottom);insets
        }
    }
    fun render(state: SettingsState) {
        rendering=true
        network.text=state.connection;connect.isEnabled=state.ready && !state.connectionBusy && !state.connected
        connect.text=if(state.connectionBusy)"Connecting…" else "Connect"
        connect.visibility=if(state.connected)View.GONE else View.VISIBLE
        disconnect.visibility=if(state.connected || state.connectionBusy)View.VISIBLE else View.GONE
        pair.isEnabled=state.ready && !state.connectionBusy
        auto.isChecked=state.autoConnect && state.concurrent;auto.isEnabled=state.ready && state.concurrent;autoDetail.text=if(state.concurrent) "Reconnect when KigEyes is nearby and the app is open. Stay connected also allows this in the background." else "Automatic reconnect is disabled on this phone."
        capability.text=if(state.concurrent)"WiFi STA concurrency supported · Connecting will not interrupt your current WiFi connection." else "WiFi STA concurrency not supported. Connecting will replace your current WiFi connection."
        capability.setTextColor(if(state.concurrent)muted else color(androidx.appcompat.R.attr.colorError))
        background.isChecked=state.background
        worker.text=listOf(state.worker.title,state.worker.detail).joinToString("\n")
        sharing.isChecked=state.worker.allowed;sharing.isEnabled=state.ready && !state.workerBusy
        screenOff.text=if(state.worker.screenOffSupported) "Screen off while shooting…" else "Update worker for screen-off mode…"
        screenOff.isEnabled=state.worker.available && (!state.worker.screenOffSupported || state.worker.streaming) && !state.workerBusy && state.worker.screenGuard!=1
        stop.isEnabled=state.worker.available && !state.worker.stopping && !state.workerBusy
        slots.check(if(state.slot==1)one else two)
        for(i in 0 until slots.childCount)slots.getChildAt(i).isEnabled=state.slotEnabled
        pairCamera.visibility=if(state.canPairCamera)View.VISIBLE else View.GONE
        pairing.text=state.pairing;code.text=state.code;code.visibility=if(state.code.isEmpty())View.GONE else View.VISIBLE
        rendering=false
    }
}
