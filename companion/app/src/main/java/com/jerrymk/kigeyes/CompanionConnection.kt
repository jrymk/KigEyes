package com.jerrymk.kigeyes

import android.content.Context
import org.json.JSONObject
import java.io.Closeable

/** Owns provisioning/discovery and the app's Network lease, independently of Camera2.
 * CameraService hosts this owner for compatibility; MirrorStatusService retains that service.
 * No second service, recovery loop, camera session or privileged worker is created here.
 */
internal class CompanionConnection(
    context: Context,
    onAddress: (String?) -> Unit,
    streamToken: () -> String,
    telemetry: () -> JSONObject,
    remote: (JSONObject) -> Unit,
    remoteZoom: (JSONObject) -> Unit,
    provisioningFinished: (Boolean) -> Unit
): Closeable {
    private val hotspot = HotspotStore(context)
    val wifi = WifiLink(context, onAddress)
    val companion = CompanionLink(context, wifi, streamToken, telemetry, remote, remoteZoom)
    val ble = BleProvisioner(context, { companion.provisioningPayload() }, { data,peer ->
        val ssid = data.getString("ssid")
        val password = data.getString("password")
        companion.provisionedGateway(data.getString("gateway"))
        hotspot.save(ssid, password)
        hotspot.peer=peer
        wifi.forgetAccessPoint(ssid)
        connect()
    }, provisioningFinished)

    private val policy=AutoConnectPolicy()
    var visible=false
    var background=false
    private val presence: BlePresence=BlePresence(context) {
        if(policy.nearby(hotspot.autoConnect,visible || background,wifi.concurrent,
            presenceTrusted(),wifi.phase,ble.busy)) {
            presenceStop()
            runCatching { val (ssid,password)=hotspot.read();wifi.connect(ssid,password) }
                .onFailure { policy.suspend() }
        }
    }
    private fun presenceTrusted()=hotspot.read().second.isNotEmpty() && presence.trusted(hotspot.peer)
    private fun presenceStop()=presence.stop()
    var autoConnect: Boolean
        get()=hotspot.autoConnect
        set(value) { hotspot.autoConnect=value;if(value)policy.resume();reconcile() }
    val summary: String get() {
        val bt=if(ble.busy)"Pairing" else presence.connectionLabel(hotspot.peer)
        val route=when { wifi.phase==WifiPhase.CONNECTED->if(wifi.direct)"KigEyes hotspot" else "Current network";wifi.phase==WifiPhase.CONNECTING->"Connecting";else->"Disconnected" }
        return "${companion.deviceName}\nBluetooth: $bt · Wi-Fi: $route"
    }
    val autoStatus: String get()=when {
        !wifi.concurrent->"Tap Connect to join; this phone cannot keep two Wi-Fi connections."
        !hotspot.autoConnect->"Automatic connection is off."
        hotspot.peer==null->"Pair once over Bluetooth to enable automatic connection."
        policy.suspended->"Automatic connection paused · tap Connect to retry."
        !presenceTrusted()->"Enable Bluetooth and Nearby devices permission for automatic connection."
        else->presence.status.ifEmpty { "Reconnect to your paired KigEyes when it is nearby." }
    }
    fun reconcile() {
        policy.observe(wifi.phase)
        presence.watch(if(policy.shouldScan(hotspot.autoConnect,visible || background,wifi.concurrent,
            presenceTrusted(),wifi.phase,ble.busy))hotspot.peer else null)
    }
    fun connect() { policy.resume();presence.stop();val (ssid,password)=hotspot.read();wifi.connect(ssid,password) }
    fun disconnect() { policy.suspend();presence.stop();ble.cancel();wifi.close() }
    fun useCurrentWifi() { policy.suspend();presence.stop();wifi.useCurrentWifi() }
    fun provision() { policy.resume();presence.stop();ble.start() }

    init { WorkerNetworkBridge.wifi = wifi }

    override fun close() {
        if (WorkerNetworkBridge.wifi === wifi) WorkerNetworkBridge.wifi = null
        presence.stop()
        ble.cancel()
        companion.close()
        wifi.destroy()
    }
}
