package com.jerrymk.kigeyes

import android.content.Context
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors

/** Home presentation only. Activity actions and the existing services own all operations. */
class CompanionHome(private val context: Context,onConnect: ()->Unit,onSettings: (View)->Unit,
                    onShare: (HomeShareAction)->Unit,onCamera: ()->Unit,onViewer: ()->Unit = {}) {
    private fun dp(n: Int)=(n*context.resources.displayMetrics.density).toInt()
    private fun color(attr: Int)=MaterialColors.getColor(context,attr,javaClass.simpleName)
    private val ink=color(com.google.android.material.R.attr.colorOnSurface)
    private val muted=color(com.google.android.material.R.attr.colorOnSurfaceVariant)
    private val primary=color(androidx.appcompat.R.attr.colorPrimary)
    private val surface=color(com.google.android.material.R.attr.colorSurface)
    private val container=color(com.google.android.material.R.attr.colorSurfaceContainer)
    val root=FrameLayout(context).apply { setBackgroundColor(surface) }
    private val sharingTitle=text("Checking screen sharing…",26)
    private val sharingDetail=text("Looking for the phone worker.",14,muted).apply { minLines=2 }
    private var shareState=HomeSharingState.from(MirrorWorkerState(checking=true))
    private val share=button("Checking…",R.drawable.ic_home_play) { onShare(shareState.action) }
    private val wifiTitle=text("Connect to KigEyes",21)
    private val wifiDetail=text("A direct Wi-Fi link to your glasses.",14,muted).apply { minLines=2 }
    private val connect=button("Connect to KigEyes",R.drawable.ic_home_wifi,onConnect)
    private val cameraDetail=text("Camera streaming, photos & local recording",14,muted)

    private fun text(value: String,size: Int,tint: Int=ink)=TextView(context).apply {
        text=value;textSize=size.toFloat();setTextColor(tint)
        if(size>=20)typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
    }
    private fun column()=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL }
    private fun button(label: String,iconId: Int,click: ()->Unit)=MaterialButton(context).apply {
        text=label;isAllCaps=false;textSize=16f;minHeight=dp(56);cornerRadius=dp(18)
        icon=context.getDrawable(iconId);iconSize=dp(22);iconGravity=MaterialButton.ICON_GRAVITY_TEXT_START
        setOnClickListener { click() }
    }
    private fun card(content: LinearLayout,accent: Boolean=false)=MaterialCardView(context).apply {
        radius=dp(24).toFloat();cardElevation=0f;strokeWidth=0
        setCardBackgroundColor(if(accent)color(com.google.android.material.R.attr.colorPrimaryContainer) else container)
        content.setPadding(dp(22),dp(22),dp(22),dp(22));addView(content)
    }
    private fun LinearLayout.item(view: View,top: Int=0) {
        addView(view,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(top) })
    }
    private fun eyebrow(label: String,icon: Int)=LinearLayout(context).apply {
        gravity=Gravity.CENTER_VERTICAL
        addView(ImageView(context).apply { setImageResource(icon);imageTintList=ColorStateList.valueOf(primary);importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO },LinearLayout.LayoutParams(dp(24),dp(24)))
        addView(text(label,12,primary).apply { letterSpacing=.1f },LinearLayout.LayoutParams(-2,-2).apply { leftMargin=dp(10) })
    }
    init {
        val scroll=ScrollView(context).apply { isFillViewport=true;clipToPadding=false }
        root.addView(scroll,FrameLayout.LayoutParams(-1,-1))
        val body=column().apply { setPadding(dp(20),dp(12),dp(20),dp(24)) }
        val centered=FrameLayout(context)
        centered.addView(body,FrameLayout.LayoutParams(-1,-2,Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        centered.addOnLayoutChangeListener { _,l,_,r,_,_,_,_,_->
            val wanted=minOf(r-l,dp(1000));if(body.layoutParams.width!=wanted) { body.layoutParams= (body.layoutParams as FrameLayout.LayoutParams).apply { width=wanted } }
        }
        scroll.addView(centered)
        val heading=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL }
        heading.addView(text("KigEyes",30),LinearLayout.LayoutParams(0,-2,1f))
        heading.addView(MaterialButton(context,null,com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            contentDescription="View wearer stream";tooltipText="Wearer view"
            icon=context.getDrawable(R.drawable.ic_home_eye);iconSize=dp(24);iconPadding=0
            minWidth=dp(48);minimumWidth=dp(48);setPadding(dp(12),0,dp(12),0)
            setOnClickListener { onViewer() }
        },LinearLayout.LayoutParams(dp(48),dp(48)).apply { rightMargin=dp(8) })
        heading.addView(MaterialButton(context,null,com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text="Settings";isAllCaps=false;minHeight=dp(48);icon=context.getDrawable(R.drawable.ic_home_settings);iconSize=dp(20)
            setOnClickListener { onSettings(it) }
        })
        body.item(heading)
        val hero=column().apply {
            item(eyebrow("SCREEN SHARING",R.drawable.ic_phone_screen))
            item(sharingTitle,18);item(sharingDetail,8);item(share,18)
        }
        val wifi=column().apply {
            item(eyebrow("CONNECTION",R.drawable.ic_home_wifi))
            item(wifiTitle,18);item(wifiDetail,8);item(connect,18)
        }
        val panels=LinearLayout(context)
        val wide=context.resources.configuration.screenWidthDp>=600
        panels.orientation=if(wide)LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        for((index,panel) in listOf(card(hero,true),card(wifi)).withIndex()) {
            panels.addView(panel,if(wide)LinearLayout.LayoutParams(0,-1,1f).apply { if(index>0)leftMargin=dp(16) }
                else LinearLayout.LayoutParams(-1,-2).apply { if(index>0)topMargin=dp(16) })
        }
        body.item(panels,24)
        val cameraBody=column().apply {
            item(eyebrow("PHONE CAMERA",R.drawable.ic_home_camera));item(cameraDetail,12)
            item(MaterialButton(context,null,com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text="Open camera";isAllCaps=false;minHeight=dp(48);icon=context.getDrawable(R.drawable.ic_home_camera)
                setOnClickListener { onCamera() }
            },12)
        }
        body.item(card(cameraBody),24)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view,insets->
            val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left,bars.top,bars.right,bars.bottom);insets
        }
    }
    fun render(state: CompanionHomeState) {
        shareState=state.sharing
        sharingTitle.text=shareState.title;sharingDetail.text=shareState.detail
        share.text=shareState.button;share.isEnabled=shareState.enabled
        share.setIconResource(when(shareState.action) { HomeShareAction.PAUSE->R.drawable.ic_home_pause;HomeShareAction.ALLOW->R.drawable.ic_home_play;HomeShareAction.SETUP->R.drawable.ic_phone_screen })
        wifiTitle.text=state.connectionTitle;wifiDetail.text=state.connectionDetail
        connect.text=state.connectionButton;connect.isEnabled=state.connectionEnabled
        connect.visibility=if(state.connectionButton=="Connected")View.GONE else View.VISIBLE
        cameraDetail.text=state.cameraSummary
    }
}
