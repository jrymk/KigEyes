package com.jerrymk.kigeyes

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.google.android.material.color.DynamicColors

/** Debug-only visual fixture: never binds services, starts capture or requests a network. */
class HomePreviewActivity: AppCompatActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if(intent.getBooleanExtra("landscape",false))requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        delegate.localNightMode=if(intent.getBooleanExtra("dark",false))androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES else androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
        DynamicColors.applyToActivityIfAvailable(this)
        WindowCompat.setDecorFitsSystemWindows(window,false)
        WindowCompat.getInsetsController(window,window.decorView).apply { isAppearanceLightStatusBars=!intent.getBooleanExtra("dark",false);isAppearanceLightNavigationBars=isAppearanceLightStatusBars }
        val scene=intent.getStringExtra("scene") ?: "sharing"
        val worker=when(scene) {
            "setup","worker-setup"->MirrorWorkerState()
            "paused"->MirrorWorkerState(true,false,true,false)
            "waiting"->MirrorWorkerState(true,true,false,false)
            else->MirrorWorkerState(true,true,true,true,networkRoute="companion_wifi")
        }
        if(scene=="settings" || scene=="single-wifi") {
            val settings=SettingsPage(this,{finish()},{},{_,_->},{})
            setContentView(settings.root)
            settings.render(SettingsState(connection="KigEyes\nBluetooth: Paired · Wi-Fi: KigEyes hotspot",connected=true,concurrent=scene!="single-wifi",
                autoDetail="Reconnect to your paired KigEyes when it is nearby.",
                worker=MirrorWorkerState(true,true,true,true,version="test"),
                pairing="Paired · Camera 1",slotEnabled=true,ready=true))
            return
        }
        val connected=scene !in listOf("setup","connecting","waiting")
        val view=CompanionHome(this,{}, {}, {}, {})
        setContentView(view.root)
        if(scene=="worker-setup")SetupSheet(this,{},{}).also {
            it.show();it.render("Set up screen sharing","1. Enable USB debugging in Developer options.\n2. Plug this phone into KigEyes.\n3. Approve debugging on this phone.\n\nSharing starts only when you choose it.","Ready — install on this phone",secondaryLabel="Open Developer options")
        }
        if(scene=="connecting")ConnectionDialog(this,{}, {}, {}).also { it.show();it.render(true,"Approve Android’s Wi-Fi request if prompted. Your saved KigEyes password is supplied automatically.") }
        view.render(CompanionHomeState(HomeSharingState.from(worker),
            if(connected)"KigEyes Wi-Fi connected" else if(scene=="connecting")"Connecting…" else "Connect to KigEyes",
            if(connected)"Direct link ready. Your other internet connection stays available." else "Use your saved KigEyes hotspot. Your normal Wi-Fi can stay connected.",
            if(connected)"Connected" else "Connect to KigEyes",!connected,
            if(scene=="recording")"Recording on phone · 12m 34s" else "Camera streaming, photos & local recording"))
    }
}
