package com.jerrymk.kigeyes

import android.content.Context
import android.net.*
import android.net.wifi.*
import android.os.Handler
import android.os.Looper
import java.io.Closeable
import java.net.Inet4Address
import java.net.Socket
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WifiLink(context: Context, private val onAddress: (String?) -> Unit) : Closeable {
    private val cm=context.getSystemService(ConnectivityManager::class.java)
    private val wifi=context.getSystemService(WifiManager::class.java)
    private val preferences=context.getSharedPreferences("wifi",0)
    private val main=Handler(Looper.getMainLooper())
    @Volatile private var epoch=0
    private var callback: ConnectivityManager.NetworkCallback?=null
    @Volatile var status="Not connected to KigEyes"; private set
    @Volatile var phase=WifiPhase.IDLE; private set
    @Volatile var direct=false; private set
    val concurrent get()=wifi.isStaConcurrencyForLocalOnlyConnectionsSupported
    @Volatile var localAddress: String?=null; private set
    @Volatile private var link: Network?=null
    val network get()=link
    val isSecondary get()=link?.let { it!=cm.activeNetwork } ?: false
    val gateway: String? get()=link?.let { cm.getLinkProperties(it)?.routes?.firstOrNull { r -> r.isDefaultRoute && r.gateway is Inet4Address }?.gateway?.hostAddress }
    @Volatile var probe=""; private set
    private val checks=Executors.newSingleThreadScheduledExecutor()
    init { checks.scheduleWithFixedDelay({
        val network=link
        if(network!=null && localAddress?.startsWith("10.42.0.")==true) {
            try {
                network.socketFactory.createSocket().use { socket ->
                    socket.soTimeout=1500
                    socket.connect(InetSocketAddress("10.42.0.1",80),1500)
                    socket.getOutputStream().write("GET / HTTP/1.0\r\nHost: 10.42.0.1\r\n\r\n".toByteArray())
                    probe="Pi reachable · ${socket.getInputStream().bufferedReader().readLine()?.take(40)}"
                }
            } catch(e: Exception) { probe="Pi connection: ${e.message}" }
        }
    },1,10,TimeUnit.SECONDS) }
    fun bind(socket: Socket) { link?.bindSocket(socket) }
    fun destroy() { close(); checks.shutdownNow() }
    val primaryStatus: String get() {
        val network=cm.activeNetwork ?: return "No default internet network"
        val caps=cm.getNetworkCapabilities(network) ?: return "No default network capabilities"
        val kind=when { caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)->"Wi-Fi";caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)->"Mobile data";else->"Other" }
        return "$kind · ${if(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) "internet validated" else "internet unverified"} · $network"
    }
    fun forgetAccessPoint(ssid: String) { preferences.edit().remove(ssid).apply() }
    fun connect(ssid: String, password: String) {
        close()
        try { requestHotspot(ssid,password) }
        catch(e: Exception) { close();phase=WifiPhase.FAILED;status="Could not join KigEyes · tap Connect to retry";throw e }
    }
    private fun requestHotspot(ssid: String,password: String) {
        require(ssid.isNotEmpty() && password.length in 8..63) { "Pair KigEyes again to refresh its Wi-Fi details" }
        phase=WifiPhase.CONNECTING;direct=true
        val builder=WifiNetworkSpecifier.Builder().setSsid(ssid)
        if(password.isNotEmpty()) builder.setWpa2Passphrase(password)
        preferences.getString(ssid,null)?.let { runCatching { builder.setBssid(MacAddress.fromString(it)) } }
        val request=NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).setNetworkSpecifier(builder.build()).build()
        status="Approve the Android Wi-Fi connection dialog"
        val serial=epoch
        val cb=object: ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
            override fun onAvailable(network: Network) { if(serial==epoch) update(network,cm.getLinkProperties(network)) }
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) { if(serial==epoch) update(network,properties) }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if(serial!=epoch) return
                val bssid=(capabilities.transportInfo as? WifiInfo)?.bssid
                if(bssid!=null && bssid!="02:00:00:00:00:00") preferences.edit().putString(ssid,bssid).apply()
            }
            override fun onLost(network: Network) { if(serial==epoch && link==network) {
                close();status="KigEyes disconnected"
                // The connection owner waits for paired BLE presence before requesting again.
            } }
            override fun onUnavailable() { if(serial==epoch) { close();phase=WifiPhase.FAILED;status="Connection unavailable or declined · tap Connect to retry" } }
        }
        callback=cb
        // Bind only camera traffic to this network, never change the process/default network.
        cm.requestNetwork(request,cb,main,45_000)
        // Android's request timeout ends at onAvailable; also bound waiting for usable IPv4.
        main.postDelayed({
            if(serial==epoch && phase==WifiPhase.CONNECTING) {
                close();phase=WifiPhase.FAILED;status="Connection timed out · tap Connect to retry"
            }
        },45_000)
    }
    private fun update(network: Network, props: LinkProperties?) {
        val ip=props?.linkAddresses?.firstOrNull { it.address is Inet4Address }?.address?.hostAddress ?: return
        val changed = NetworkLeaseChange.changed(link?.networkHandle, localAddress, network.networkHandle, ip)
        phase=WifiPhase.CONNECTED;link=network; status="KigEyes local Wi-Fi · $ip · $network"
        localAddress=ip
        if(changed) onAddress(ip)
    }
    fun useCurrentWifi() {
        close()
        val network=cm.activeNetwork ?: error("No active network")
        check(cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true) { "Connect to Wi-Fi first" }
        update(network,cm.getLinkProperties(network))
        status="Existing Wi-Fi · $localAddress"
        val serial=epoch
        val cb=object: ConnectivityManager.NetworkCallback() {
            override fun onLost(n: Network) { if(serial==epoch && n==link) { close();status="Wi-Fi lost" } }
            override fun onLinkPropertiesChanged(n: Network,p: LinkProperties) { if(serial==epoch && n==network) update(n,p) }
        }
        callback=cb; cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),cb,main)
    }
    override fun close() {
        epoch++;phase=WifiPhase.IDLE;direct=false; main.removeCallbacksAndMessages(null)
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }; callback=null
        link=null; localAddress=null; probe=""; status="Not connected to KigEyes"; onAddress(null)
    }
}
