package com.jerrymk.kigeyes

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.*
import android.widget.*
import androidx.appcompat.widget.AppCompatImageButton
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** GL alone fits the image into the viewfinder: no second aspect-fit or TextureView transform. */
object PreviewGeometry {
    fun rotation(sensorDegrees: Int,displayDegrees: Int)=(sensorDegrees-displayDegrees+360)%360
    fun fit(width: Int,height: Int,rotation: Int=0): Pair<Int,Int> {
        val w=if(rotation%180==0)16 else 9
        val h=if(rotation%180==0)9 else 16
        val scale=minOf(width.toFloat()/w,height.toFloat()/h)
        return (w*scale).toInt() to (h*scale).toInt()
    }
}

/** Camera edge controls with lightweight status overlays and secondary setup in dialogs. */
class CameraScreen(val context: Context,selected: CaptureQuality,private val onStart: ()->Unit,onRecord: ()->Unit,
                   onConnect: ()->Unit,private val onQuality: (CaptureQuality)->Unit,onDiagnostics: ()->Unit,onPhoto: ()->Unit,onZoom: (Float)->Unit,private val onProcessing: (Boolean,Boolean)->Unit,private val onNightPhoto: ()->Unit,private val onMirror: ()->Unit,onHome: ()->Unit) {
    private fun dp(n: Int)=(context.resources.displayMetrics.density*n).toInt()
    private val white=Color.rgb(245,247,246)
    private val muted=Color.rgb(174,183,180)
    private val green=Color.rgb(180,235,202)
    private val red=Color.rgb(255,91,104)
    private val landscape=context.resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE
    private var quality=selected
    private val preferences=context.getSharedPreferences("captureProcessing",0)
    private var recordingMode=RecordingMode.parse(preferences.getString("recordingMode",null))
    private var enhancedPhotos=preferences.getBoolean("enhancedPhotos",true)
    private var videoDenoise=preferences.getBoolean("videoDenoise",false)
    private var capabilities=""
    private var nightAvailable=false
    private var canNight=false
    private fun qualityLabel()=(if(quality==CaptureQuality.UHD) "4K" else "1080p")+when(recordingMode) {
        RecordingMode.TIMELAPSE->" · 15×"
        RecordingMode.LOW_FPS->" · 5 fps"
        else->""
    }
    private var running=false
    private var starting=false
    private var lastPhotoStatus=""
    private var lastRecordStatus=""
    private var messageUntil=0L
    private var savedMessage=""
    private var details=""
    private var streamLabel="540p30"
    val root=FrameLayout(context).apply { setBackgroundColor(Color.rgb(12,16,15)) }
    val preview=TextureView(context)
    private val zoom=ZoomControls(context,preview,onZoom)
    private val flash=View(context).apply { setBackgroundColor(Color.WHITE);alpha=0f;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
    private val empty=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setBackgroundColor(Color.rgb(19,25,23)) }
    private val start=MaterialButton(context).apply {
        text="Start camera";isAllCaps=false;textSize=18f;cornerRadius=dp(28)
        backgroundTintList=ColorStateList.valueOf(green);setTextColor(Color.rgb(18,40,29))
        setOnClickListener { startIfStopped() }
    }
    private val connection=label("Connect KigEyes",14,green)
    private val qualityButton=label("1080p",14,white)
    private val state=label("Photos and videos save on this phone",12,muted)
    private val elapsed=label("",14,red)
    private val photo=CaptureButton(context,false)
    private val record=CaptureButton(context,true)
    private val photoLabel=label("Photo",12,white)
    private val recordLabel=label("Video",12,white)
    private val viewers=label("0",14,white)
    init {
        val top=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL;setPadding(dp(12),0,dp(4),0) }
        connection.apply {
            maxLines=1;maxWidth=dp(if(landscape)300 else 230);ellipsize=android.text.TextUtils.TruncateAt.END;gravity=Gravity.CENTER_VERTICAL
            setPadding(dp(12),0,dp(8),0);background=pill(Color.rgb(30,40,35));setOnClickListener { onConnect() }
            contentDescription="KigEyes connection setup";isFocusable=true
        }
        top.addView(AppCompatImageButton(context).apply {
            setImageResource(R.drawable.ic_home_arrow_left);imageTintList=ColorStateList.valueOf(white)
            setBackgroundColor(Color.TRANSPARENT);contentDescription="Back to KigEyes";setOnClickListener { onHome() }
        },LinearLayout.LayoutParams(dp(48),dp(48)))
        top.addView(connection,LinearLayout.LayoutParams(0,dp(44),1f))
        qualityButton.apply { gravity=Gravity.CENTER;setPadding(dp(12),0,dp(12),0);setOnClickListener { settings() };contentDescription="Recording quality and camera settings";isFocusable=true }
        top.addView(qualityButton,LinearLayout.LayoutParams(-2,dp(48)))
        val more=label("⋮",28,white).apply {
            gravity=Gravity.CENTER;contentDescription="More camera options";isFocusable=true
            setOnClickListener { anchor ->
                PopupMenu(context,anchor).apply {
                    if(nightAvailable)menu.add("Night photo · pauses stream").apply {
                        isEnabled=canNight;setOnMenuItemClickListener { onNightPhoto();true }
                    }
                    menu.add("KigEyes home").setOnMenuItemClickListener { onMirror();true }
                    menu.add("Camera settings").setOnMenuItemClickListener { settings();true }
                    menu.add("Connection setup").setOnMenuItemClickListener { onConnect();true }
                    menu.add("Diagnostics").setOnMenuItemClickListener { onDiagnostics();true }
                    if(running) menu.add("Stop camera").setOnMenuItemClickListener { onStart();true }
                }.show()
            }
        }
        top.addView(more,LinearLayout.LayoutParams(dp(48),dp(48)))
        top.background=fade(GradientDrawable.Orientation.TOP_BOTTOM)
        val viewfinder=FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
            addView(preview,FrameLayout.LayoutParams(-1,-1))
            empty.addView(label("Phone camera",24,white).apply { gravity=Gravity.CENTER },LinearLayout.LayoutParams(-1,-2))
            empty.addView(label("Stream to your glasses. Capture on your phone.",13,muted).apply { gravity=Gravity.CENTER;setPadding(dp(20),dp(12),dp(20),dp(20)) })
            empty.addView(start,LinearLayout.LayoutParams(dp(220),dp(64)))
            empty.addView(MaterialButton(context,null,com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { text="Back to KigEyes";isAllCaps=false;setOnClickListener { onMirror() } },LinearLayout.LayoutParams(dp(220),dp(52)))
            empty.setOnClickListener { startIfStopped() }
            addView(empty,FrameLayout.LayoutParams(-1,-1))
            addView(flash,FrameLayout.LayoutParams(-1,-1))
            elapsed.apply { gravity=Gravity.CENTER;setPadding(dp(14),dp(5),dp(14),dp(5));background=pill(0xDD161A18.toInt());visibility=View.GONE }
            addView(elapsed,FrameLayout.LayoutParams(-2,dp(34),Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin=dp(64) })
        }
        val body=LinearLayout(context).apply { orientation=if(landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
        root.addView(body,FrameLayout.LayoutParams(-1,-1))
        body.addView(viewfinder,if(landscape) LinearLayout.LayoutParams(0,-1,1f) else LinearLayout.LayoutParams(-1,0,1f))
        viewfinder.addView(top,FrameLayout.LayoutParams(-1,dp(56),Gravity.TOP))
        val controls=LinearLayout(context).apply {
            orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(dp(8),dp(8),dp(8),dp(8))
        }
        val captureRow=LinearLayout(context).apply { orientation=if(landscape) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL;gravity=Gravity.CENTER }
        fun capture(button: CaptureButton,label: TextView,size: Int) {
            val group=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER }
            group.addView(button,LinearLayout.LayoutParams(dp(size),dp(size)))
            label.apply { gravity=Gravity.CENTER;setPadding(0,dp(6),0,0) }
            group.addView(label,LinearLayout.LayoutParams(-1,dp(28)))
            captureRow.addView(group,LinearLayout.LayoutParams(if(landscape) dp(96) else dp(120),-2).apply {
                if(landscape) { topMargin=dp(6);bottomMargin=dp(6) }
            })
        }
        capture(photo,photoLabel,78);capture(record,recordLabel,64)
        photo.apply { contentDescription="Take photo";setOnClickListener { onPhoto() };isEnabled=false }
        record.apply { contentDescription="Start recording video";setOnClickListener { onRecord() };isEnabled=false }
        controls.addView(captureRow)
        body.addView(controls,if(landscape) LinearLayout.LayoutParams(dp(116),-1) else LinearLayout.LayoutParams(-1,dp(126)))
        viewers.apply {
            gravity=Gravity.CENTER;compoundDrawablePadding=dp(6);setPadding(dp(10),0,dp(10),0);background=pill(0xB3161A18.toInt())
            val icon=context.getDrawable(R.drawable.ic_viewers)!!.apply { setBounds(0,0,dp(28),dp(28)) }
            setCompoundDrawables(icon,null,null,null)
        }
        viewfinder.addView(viewers,FrameLayout.LayoutParams(-2,dp(40),Gravity.BOTTOM or Gravity.LEFT).apply { leftMargin=dp(12);bottomMargin=dp(12) })
        viewfinder.addView(zoom.view,FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin=dp(12) })
        state.apply { gravity=Gravity.CENTER;maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END;setPadding(dp(12),dp(4),dp(12),dp(4));accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE;background=pill(0xB3161A18.toInt()) }
        viewfinder.addView(state,FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { leftMargin=dp(12);rightMargin=dp(12);bottomMargin=dp(98) })
        ViewCompat.setOnApplyWindowInsetsListener(root) { v,insets ->
            val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left,bars.top,bars.right,bars.bottom)
            insets
        }
    }
    private fun startIfStopped() {
        if(running || starting)return
        starting=true;start.isEnabled=false;start.text="Starting…"
        onStart()
        // Permit retry after permission dismissal or a failed start.
        root.postDelayed({ starting=false;if(!running){start.isEnabled=true;start.text="Start camera"} },2000)
    }
    fun photoCaptured() {
        flash.animate().cancel();flash.alpha=.55f;flash.animate().alpha(0f).setDuration(160).start()
    }
    fun photoFinished(problem: String?) {
        savedMessage=problem ?: "Photo saved to Pictures/KigEyes"
        messageUntil=android.os.SystemClock.elapsedRealtime()+4000
    }
    private fun label(value: String,size: Int,color: Int)=TextView(context).apply {
        text=value;textSize=size.toFloat();setTextColor(color);includeFontPadding=false
        typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
        setShadowLayer(dp(2).toFloat(),0f,1f,Color.BLACK)
    }
    private fun pill(color: Int)=GradientDrawable().apply { setColor(color);cornerRadius=dp(24).toFloat() }
    private fun fade(direction: GradientDrawable.Orientation)=GradientDrawable(direction,intArrayOf(0xB3000000.toInt(),Color.TRANSPARENT))
    private fun settings() {
        val items=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(24),dp(8),dp(24),dp(8)) }
        fun heading(text: String) { items.addView(TextView(context).apply { this.text=text;setPadding(0,dp(12),0,dp(4)) }) }
        heading(if(running) "Stop the camera to change resolution or storage mode." else "Recording resolution")
        val estimates=mutableListOf<Pair<RadioButton,RecordingMode>>()
        val choices=RadioGroup(context)
        listOf(CaptureQuality.FULL_HD to "1080p",CaptureQuality.UHD to "4K").forEach { (value,title) ->
            val radio=RadioButton(context).apply { id=View.generateViewId();text=title;isEnabled=!running }
            choices.addView(radio);if(value==quality)choices.check(radio.id)
            radio.setOnClickListener {
                quality=value;qualityButton.text=qualityLabel();onQuality(value)
                estimates.forEach { (button,mode)->button.text="${mode.title}\n${mode.description(value)}" }
            }
        }
        items.addView(choices)
        heading("Local recording storage")
        val modes=RadioGroup(context)
        RecordingMode.entries.forEach { value ->
            val radio=RadioButton(context).apply {
                id=View.generateViewId();text="${value.title}\n${value.description(quality)}";isEnabled=!running
                setPadding(dp(4),dp(6),0,dp(6));textSize=14f
            }
            estimates.add(radio to value);modes.addView(radio);if(value==recordingMode)modes.check(radio.id)
            radio.setOnClickListener {
                recordingMode=value;preferences.edit().putString("recordingMode",value.name).apply();qualityButton.text=qualityLabel()
            }
        }
        items.addView(modes)
        heading("Image processing")
        items.addView(androidx.appcompat.widget.SwitchCompat(context).apply {
            text="Enhanced photos";isChecked=enhancedPhotos
            setOnCheckedChangeListener { _,checked->enhancedPhotos=checked;onProcessing(enhancedPhotos,videoDenoise) }
        })
        items.addView(androidx.appcompat.widget.SwitchCompat(context).apply {
            text="Stronger video noise reduction";isChecked=videoDenoise
            setOnCheckedChangeListener { _,checked->videoDenoise=checked;onProcessing(enhancedPhotos,videoDenoise) }
        })
        heading("Enhanced photos use supported high-quality camera processing, not Pixel HDR+/Night Sight. Video noise reduction may soften detail or lower frame rate; turn it off if motion suffers.")
        heading("$streamLabel streaming is separate. Storage figures are targets, not guarantees. Low fps keeps normal duration; time-lapse plays 15× faster. Videos currently have no audio.\n\n$details\n$capabilities")
        val scroll=ScrollView(context).apply { addView(items) }
        MaterialAlertDialogBuilder(context).setTitle("Camera settings").setView(scroll).setPositiveButton("Done",null).apply {
            if(running)setNeutralButton("Stop camera") { _,_->onStart() }
        }.show()
    }
    fun render(s: CameraService) {
        val p=s.pipeline
        nightAvailable=p?.nightAvailable==true
        canNight=nightAvailable && p?.ready==true && !s.recordRequested && !s.photoBusy
        streamLabel=p?.streamQuality?.label ?: "540p30"
        zoom.render(p?.zoomRange ?: CameraZoom(),p?.zoomRatio ?: 1f,p?.ready==true && !p.fault && !p.recovering)
        running=p!=null
        empty.visibility=if(running) View.GONE else View.VISIBLE
        if(running)starting=false
        if(!starting){start.isEnabled=true;start.text="Start camera"}
        quality=p?.recordQuality ?: quality
        recordingMode=p?.recordingMode ?: recordingMode
        enhancedPhotos=p?.enhancedPhotos ?: preferences.getBoolean("enhancedPhotos",true)
        videoDenoise=p?.videoDenoise ?: preferences.getBoolean("videoDenoise",false)
        capabilities=p?.let { "${it.processingStatus}\nPhoto: ${it.photoProcessingStatus}\n${it.processingCapabilities}\nSupported photo extensions: ${it.extensionCapabilities}" } ?: ""
        qualityButton.text=qualityLabel()
        val seconds=s.recorder.durationUs/1_000_000
        elapsed.text="●  %02d:%02d".format(seconds/60,seconds%60)+if(recordingMode==RecordingMode.TIMELAPSE) " → ${s.recorder.playbackDurationUs/1_000_000}s video" else ""
        elapsed.visibility=if(s.recordRequested) View.VISIBLE else View.GONE
        record.recording=s.recordRequested
        val recordingBlocked=if(s.recordRequested)null else s.recordingBlockedReason
        record.contentDescription=if(s.recordRequested) "Stop and save video" else "Start recording video"
        recordLabel.text=if(s.recordRequested) "Stop" else "Video"
        record.isEnabled=s.recordRequested || recordingBlocked==null
        photo.isEnabled=p?.ready==true && p.photoSize!=null && !s.photoBusy
        photoLabel.text=if(s.photoBusy) "Saving…" else "Photo"
        photo.alpha=if(photo.isEnabled) 1f else .35f
        record.alpha=if(record.isEnabled) 1f else .35f
        val now=android.os.SystemClock.elapsedRealtime()
        if(s.photos.status!=lastPhotoStatus) {
            if(lastPhotoStatus.isNotEmpty() && s.photos.status.startsWith("Saved")) { savedMessage="Photo saved to Pictures/KigEyes";messageUntil=now+4000 }
            lastPhotoStatus=s.photos.status
        }
        if(s.recorder.status!=lastRecordStatus) {
            if(lastRecordStatus.isNotEmpty() && s.recorder.status.startsWith("Saved")) { savedMessage="Video saved to Movies/KigEyes";messageUntil=now+4000 }
            lastRecordStatus=s.recorder.status
        }
        state.text=when {
            running && !s.recordRequested && RecordingPolicy.tooHot(s.thermalStatus)->"Phone too hot · let it cool before recording"
            s.error.isNotEmpty()->s.error
            p?.recovering==true->"Camera interrupted · resuming…"
            p?.nightCapturing==true->"Night photo · hold still…"
            s.photoBusy->"Saving photo…"
            s.recorder.busy && !s.recordRequested->"Saving video…"
            s.companion.state=="waiting"->"Pairing code ${s.companion.code} · approve in your glasses"
            now<messageUntil->savedMessage
            p?.ready==true->""
            running->"Starting camera…"
            else->"Photos and videos save on this phone"
        }
        state.visibility=if(state.text.isEmpty())View.GONE else View.VISIBLE
        val count=s.server?.connections ?: 0
        viewers.text=count.toString();viewers.contentDescription="$count stream viewer${if(count==1) "" else "s"}"
        viewers.visibility=if(p?.ready==true)View.VISIBLE else View.GONE
        connection.text=when {
            s.companion.state=="paired"->"●  KigEyes · Camera ${s.companion.slot}"
            s.companion.state=="waiting"->"Approve pairing"
            (s.server?.connections ?: 0)>0->"●  Streaming"
            else->"Connect KigEyes"
        }
        details="${"%.1f".format(s.freeBytes/1e9)} GB free · ${s.thermalLabel}\nPhotos: Pictures/KigEyes\nVideos: Movies/KigEyes"
    }
}

/** Standard camera shutter and video/stop glyphs; both remain ordinary accessible buttons. */
private class CaptureButton(context: Context,video: Boolean): AppCompatImageButton(context) {
    private val glyph=object: Drawable() {
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        var recording=false
        override fun draw(canvas: Canvas) {
            val cx=bounds.exactCenterX();val cy=bounds.exactCenterY();val radius=minOf(bounds.width(),bounds.height())*.45f
            paint.color=Color.rgb(245,247,246);paint.style=Paint.Style.STROKE;paint.strokeWidth=radius*.065f
            canvas.drawCircle(cx,cy,radius,paint)
            paint.style=Paint.Style.FILL;paint.color=if(video)Color.rgb(255,91,104) else Color.WHITE
            if(recording) { val half=radius*.44f;canvas.drawRoundRect(cx-half,cy-half,cx+half,cy+half,radius*.12f,radius*.12f,paint) }
            else canvas.drawCircle(cx,cy,radius*(if(video).62f else .84f),paint)
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(filter: ColorFilter?) {}
        @Deprecated("Deprecated in Android") override fun getOpacity()=PixelFormat.TRANSLUCENT
    }
    var recording=false
        set(value) { if(field!=value){field=value;glyph.recording=value;invalidate()} }
    init {
        scaleType=ImageView.ScaleType.FIT_CENTER;setPadding(0,0,0,0);setImageDrawable(glyph)
        background=RippleDrawable(ColorStateList.valueOf(0x44FFFFFF),null,GradientDrawable().apply { shape=GradientDrawable.OVAL;setColor(Color.WHITE) })
        isFocusable=true
    }
}
