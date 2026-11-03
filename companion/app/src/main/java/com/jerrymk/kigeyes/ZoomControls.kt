package com.jerrymk.kigeyes

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.widget.*
import kotlin.math.abs

/** Same bounded camera zoom for quick lens shortcuts and continuous viewfinder gestures. */
class ZoomControls(private val context: Context,preview: TextureView,private val change: (Float)->Unit) {
    private fun dp(n: Int)=(context.resources.displayMetrics.density*n).toInt()
    private var range=CameraZoom()
    private var current=1f
    private var available=false
    private val buttons=mutableListOf<Pair<Float,TextView>>()
    val view=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;visibility=View.GONE }
    private val readout=TextView(context).apply {
        textSize=14f;setTextColor(Color.WHITE);gravity=Gravity.CENTER
        setShadowLayer(dp(2).toFloat(),0f,1f,Color.BLACK);setPadding(dp(8),dp(4),dp(8),dp(4))
        importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val row=LinearLayout(context).apply {
        gravity=Gravity.CENTER;setPadding(dp(4),dp(2),dp(4),dp(2))
        background=GradientDrawable().apply { setColor(0xCC161A18.toInt());cornerRadius=dp(28).toFloat() }
    }
    private val detector=ScaleGestureDetector(context,object: ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector)=available
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            request(range.pinch(current,detector.scaleFactor));return true
        }
    }).apply { isQuickScaleEnabled=false }
    init {
        view.addView(readout,LinearLayout.LayoutParams(-2,dp(28)))
        view.addView(row,LinearLayout.LayoutParams(-2,-2))
        preview.setOnTouchListener { v,event ->
            if(!available)return@setOnTouchListener false
            detector.onTouchEvent(event)
            if(event.actionMasked==MotionEvent.ACTION_UP)v.performClick()
            true
        }
    }
    fun render(bounds: CameraZoom,value: Float,enabled: Boolean) {
        available=enabled && bounds.max>bounds.min
        view.visibility=if(available)View.VISIBLE else View.GONE
        if(bounds!=range || buttons.isEmpty()) {
            range=bounds;row.removeAllViews();buttons.clear()
            for(ratio in bounds.presets) {
                val button=TextView(view.context).apply {
                    text=CameraZoom.label(ratio);textSize=14f;gravity=Gravity.CENTER
                    typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
                    contentDescription="Zoom ${CameraZoom.label(ratio)}";isFocusable=true
                    setOnClickListener { if(available)request(ratio) }
                    accessibilityDelegate=object: View.AccessibilityDelegate() {
                        override fun onInitializeAccessibilityNodeInfo(host: View,info: android.view.accessibility.AccessibilityNodeInfo) {
                            super.onInitializeAccessibilityNodeInfo(host,info);info.className="android.widget.Button"
                        }
                    }
                }
                row.addView(button,LinearLayout.LayoutParams(dp(48),dp(48)));buttons.add(ratio to button)
            }
        }
        if(!detector.isInProgress)current=range.clamp(value)
        paint()
    }
    private fun request(value: Float) { current=range.clamp(value);paint();change(current) }
    private fun paint() {
        var exact=false
        for((ratio,button) in buttons) {
            val selected=abs(current-ratio)<.015f
            exact=exact || selected;button.isEnabled=available
            if(button.isSelected!=selected || button.background==null) {
                button.isSelected=selected
                button.setTextColor(if(selected)Color.rgb(20,44,30) else Color.WHITE)
                button.background=GradientDrawable().apply {
                    shape=GradientDrawable.OVAL;setColor(if(selected)Color.rgb(180,235,202) else Color.TRANSPARENT)
                }
            }
        }
        val label=CameraZoom.label(current)
        if(readout.text.toString()!=label)readout.text=label
        readout.visibility=if(exact)View.INVISIBLE else View.VISIBLE
    }
}
