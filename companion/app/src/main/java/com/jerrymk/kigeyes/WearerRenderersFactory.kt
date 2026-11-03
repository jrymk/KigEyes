package com.jerrymk.kigeyes

import android.content.Context
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaFormat
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.mediacodec.DefaultMediaCodecAdapterFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter

/** Opt in only when the selected hardware decoder advertises low-latency operation. */
@UnstableApi
internal class WearerRenderersFactory(context: Context): DefaultRenderersFactory(context) {
    private val delegate=DefaultMediaCodecAdapterFactory(context)
    override fun getCodecAdapterFactory()=MediaCodecAdapter.Factory { configuration ->
        val supported=configuration.codecInfo.capabilities?.isFeatureSupported(CodecCapabilities.FEATURE_LowLatency)==true
        if(supported)configuration.mediaFormat.setInteger(MediaFormat.KEY_LOW_LATENCY,1)
        if(BuildConfig.DEBUG)android.util.Log.d("KigEyesWearer","decoder=${configuration.codecInfo.name} lowLatency=$supported")
        try { delegate.createAdapter(configuration) }
        catch(error: Exception) {
            if(!supported)throw error
            // A vendor may advertise the feature but reject this particular format.
            configuration.mediaFormat.removeKey(MediaFormat.KEY_LOW_LATENCY)
            delegate.createAdapter(configuration)
        }
    }
}
