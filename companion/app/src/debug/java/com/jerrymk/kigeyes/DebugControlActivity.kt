package com.jerrymk.kigeyes

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Manifest DUMP permission admits authorized adb shell, not ordinary apps.
 * The public launcher never accepts capture or network test commands.
 */
class DebugControlActivity: Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if(intent.getStringExtra("testAction")=="viewerProbe") {
            val uri=android.net.Uri.parse(intent.getStringExtra("stream") ?: "")
            require(uri.scheme=="rtsp" && uri.port==18554 && uri.path=="/test" && uri.userInfo==null && !uri.host.isNullOrEmpty())
            startActivity(Intent(this,WearerViewerActivity::class.java).putExtra("probeStream",uri.toString()))
            finish();return
        }
        if(intent.getStringExtra("testAction")=="bleScanProbe") {
            BleScanProbe.run(applicationContext)
            finish()
            return
        }
        intent.getStringExtra("testAction")?.takeIf { it.length <= 64 }?.let {
            DevelopmentActions.offer(it)
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        finish()
    }
}

internal object DevelopmentActions {
    private var pending: String? = null
    @Synchronized fun offer(action: String) { pending = action }
    @Synchronized fun take(): String? = pending.also { pending = null }
}
