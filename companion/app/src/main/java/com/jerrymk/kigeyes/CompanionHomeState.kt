package com.jerrymk.kigeyes

/** Immutable presentation snapshot; the home view has no service dependencies. */
data class CompanionHomeState(
    val sharing: HomeSharingState,
    val connectionTitle: String, val connectionDetail: String,
    val connectionButton: String, val connectionEnabled: Boolean,
    val cameraSummary: String="Camera streaming, photos & local recording"
)

internal fun companionHomeState(camera: CameraService?,worker: MirrorStatusService?,savedHotspot: Boolean): CompanionHomeState {
    val connected=camera?.wifi?.localAddress!=null
    val hotspot=connected && camera?.wifi?.direct==true
    val busy=camera?.ble?.busy==true || camera?.wifi?.phase==WifiPhase.CONNECTING
    val title=if(hotspot)"KigEyes Wi-Fi connected" else if(connected)"Using current Wi-Fi" else if(busy)"Connecting…" else "Connect to KigEyes"
    val detail=when {
        hotspot->if(camera?.wifi?.isSecondary==true) "Direct link ready. Your other internet connection stays available." else "Direct Wi-Fi link to your glasses."
        connected->"Using your current Wi-Fi. Switch to direct KigEyes Wi-Fi below."
        camera==null->"Preparing your connection…"
        busy->if(camera.ble.busy)camera.ble.message else "Approve the Wi-Fi connection on your phone."
        camera.wifi.status.let { it.contains("unavailable",true) || it.contains("failed",true) || it=="Wi-Fi lost" }->camera.wifi.status
        savedHotspot->camera.connections.autoStatus.ifEmpty { if(camera.wifi.concurrent) "Connect directly while keeping your normal Wi-Fi." else "Connect directly using the saved password. This replaces your current Wi-Fi connection." }
        else->"Pair nearby over Bluetooth. No Wi-Fi password to type."
    }
    val button=if(hotspot)"Connected" else if(busy)"Connecting…" else if(savedHotspot)"Connect to KigEyes" else "Pair KigEyes"
    val summary=when {
        camera?.recordRequested==true->{ val seconds=camera.recorder.durationUs/1_000_000;"Recording on phone · ${seconds/60}m ${seconds%60}s" }
        camera?.pipeline!=null->"Camera running · return to viewfinder"
        else->"Background camera streaming with independent photos and recording."
    }
    return CompanionHomeState(HomeSharingState.from(worker?.state ?: MirrorWorkerState(checking=true),worker?.busy==true),
        if(connected)camera?.companion?.deviceName ?: title else title,if(connected)camera?.connections?.summary ?: detail else detail,button,camera!=null && !hotspot && !busy,summary)
}
