package com.jerrymk.kigeyes

import android.app.Application
import android.content.*
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource

/** Retained across rotation; one player and one binding to the existing Network owner. */
@UnstableApi
class WearerSession(application: Application): AndroidViewModel(application) {
    private val context=application
    private var bound=false
    private var owner: CameraService?=null
    private var route=""
    private var nextDiagnosticMs=0L
    private var probeStream: android.net.Uri?=null
    fun configureProbe(uri: android.net.Uri) { check(BuildConfig.DEBUG);probeStream=uri }
    var player: ExoPlayer?=null;private set
    val name get()=if(probeStream!=null) "Viewer test" else owner?.companion?.deviceName ?: "KigEyes"
    private val connection=object: ServiceConnection {
        override fun onServiceConnected(name: ComponentName,binder: IBinder) {
            owner=(binder as CameraService.LocalBinder).service;owner?.connections?.visible=true
        }
        override fun onServiceDisconnected(name: ComponentName) { owner=null }
    }
    fun start() {
        if(probeStream!=null)return
        if(!bound)bound=context.bindService(Intent(context,CameraService::class.java),connection,Context.BIND_AUTO_CREATE)
    }
    fun update() {
        val network=if(probeStream!=null) context.getSystemService(android.net.ConnectivityManager::class.java).activeNetwork
            else owner?.wifi?.network
        if(network==null)return
        val uri=probeStream ?: owner?.companion?.host?.takeIf { it.isNotEmpty() }?.let { android.net.Uri.parse("rtsp://$it:8554/wearer") } ?: return
        val key="${network.networkHandle}:$uri"
        player?.let { current ->
            // RTSP load control does not cap its incoming sample queue or provide
            // a moving live edge. Consume backlog rather than remaining seconds late.
            val speed=WearerLatencyPolicy.speed(current.totalBufferedDuration,current.isPlaying)
            if(current.playbackParameters.speed!=speed) current.setPlaybackSpeed(speed)
            val now=android.os.SystemClock.elapsedRealtime()
            if(BuildConfig.DEBUG && now>=nextDiagnosticMs) {
                nextDiagnosticMs=now+5000
                android.util.Log.d("KigEyesWearer", "state=${current.playbackState} position=${current.currentPosition} buffered=${current.totalBufferedDuration} speed=${current.playbackParameters.speed}")
            }
        }
        if(player!=null && route==key)return
        releasePlayer();route=key
        player=ExoPlayer.Builder(context,WearerRenderersFactory(context)).setLoadControl(DefaultLoadControl.Builder()
            .setBufferDurationsMs(WearerLatencyPolicy.MIN_BUFFER_MS,WearerLatencyPolicy.MAX_BUFFER_MS,
                WearerLatencyPolicy.START_BUFFER_MS,WearerLatencyPolicy.REBUFFER_MS)
            .setPrioritizeTimeOverSizeThresholds(true).build()).build().apply {
            val source=RtspMediaSource.Factory().setSocketFactory(network.socketFactory).setForceUseRtpTcp(true)
                .setTimeoutMs(20_000).createMediaSource(MediaItem.fromUri(uri))
            setMediaSource(source);prepare();playWhenReady=true
        }
    }
    fun retry() { releasePlayer();update() }
    private fun releasePlayer() { player?.release();player=null;route="" }
    fun stop() {
        releasePlayer();owner?.connections?.visible=false;owner=null
        if(bound)context.unbindService(connection)
        bound=false
    }
    override fun onCleared() { stop() }
}
