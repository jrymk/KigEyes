package com.jerrymk.kigeyes

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Discovery never transmits credentials. Pairing is a separate wearer-approved step. */
class CompanionLink(context: Context,private val wifi: WifiLink,private val streamToken: ()->String,
                    private val telemetry: ()->JSONObject,private val remote: (JSONObject)->Unit,
                    private val remoteZoom: (JSONObject)->Unit) {
    private val prefs=context.getSharedPreferences("pairing",0)
    private val key=prefs.getString("key",null) ?: ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }.also { prefs.edit().putString("key",it).apply() }
    private val nsd=context.getSystemService(NsdManager::class.java)
    private val main=Handler(Looper.getMainLooper())
    private val worker=Executors.newSingleThreadScheduledExecutor()
    private val executor=context.mainExecutor
    private var listener: NsdManager.DiscoveryListener?=null
    @Volatile private var epoch=0
    @Volatile var state="idle";private set
    @Volatile var message="Connect to Wi-Fi to find KigEyes";private set
    @Volatile var host="";private set
    @Volatile var deviceName=prefs.getString("deviceName","KigEyes")!!;private set
    @Volatile var code="";private set
    @Volatile var slot=prefs.getInt("assignedSlot",1);private set
    @Volatile var preferredSlot=prefs.getInt("preferredSlot",slot);private set
    fun selectSlot(value: Int) {
        require(value in 1..2);preferredSlot=value;prefs.edit().putInt("preferredSlot",value).apply()
    }
    private var waitingUntil=0L
    init { worker.scheduleWithFixedDelay({
        if(state=="waiting") {
            val serial=epoch
            if(System.currentTimeMillis()>waitingUntil) { state="found";code="";message="Request expired. Try pairing again." }
            else runCatching { call(host,JSONObject().put("op","poll").put("key",key)) }.onSuccess {
                if(serial==epoch) consume(it)
            }.onFailure { if(serial==epoch) message="Waiting for KigEyes connection…" }
        } else if(state=="paired") {
            val serial=epoch
            runCatching { call(host,payload("update").put("telemetry",telemetry())) }.onSuccess { reply ->
                if(serial==epoch) {
                    slot=reply.optInt("slot",slot);message="Paired · Camera $slot"
                    reply.optJSONObject("zoom_command")?.let { command -> main.post { if(serial==epoch)remoteZoom(command) } }
                    reply.optJSONObject("command")?.let { command -> main.post { if(serial==epoch)remote(command) } }
                }
            }.onFailure { if(serial==epoch) {
                if(it.message=="Phone is not approved") { state="found";message="Camera slot reassigned. Choose a slot and request pairing again." }
                else message="Phone control link interrupted · retrying…"
            } }
        }
    },200,200,TimeUnit.MILLISECONDS) }
    private fun local(address: String): Boolean {
        val a=address.split('.').map { it.toIntOrNull() ?: return false }
        return a.size==4 && a.all { it in 0..255 } && (a[0]==10 || a[0]==192 && a[1]==168 || a[0]==172 && a[1] in 16..31)
    }
    private fun call(address: String,body: JSONObject?=null): JSONObject {
        require(local(address)) { "Use a local Wi-Fi address" }
        val network=wifi.network ?: error("Connect Wi-Fi first")
        val connection=network.openConnection(URL("http://$address/api/companion")) as HttpURLConnection
        try {
            connection.connectTimeout=1800;connection.readTimeout=2000;connection.instanceFollowRedirects=false
            if(body!=null) {
                connection.requestMethod="POST";connection.doOutput=true
                connection.setRequestProperty("Content-Type","application/json");connection.setRequestProperty("X-KigEyes-Companion","1")
                val bytes=body.toString().toByteArray();connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status=connection.responseCode
            val input=if(status in 200..299) connection.inputStream else connection.errorStream ?: error("KigEyes returned HTTP $status")
            val bytes=input.use { stream ->
                val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(1024)
                while(true) { val n=stream.read(buffer);if(n<0) break;check(out.size()+n<=4096) { "Invalid KigEyes response" };out.write(buffer,0,n) }
                out.toByteArray()
            }
            val result=JSONObject(String(bytes));check(status in 200..299) { result.optString("error","HTTP $status") }
            return result
        } finally { connection.disconnect() }
    }
    fun networkChanged() {
        epoch++;stopDiscovery();host="";code="";state="idle"
        message=if(wifi.network==null) "Connect to Wi-Fi to find KigEyes" else "Looking for KigEyes…"
        if(wifi.network!=null) discover()
    }
    fun discover(manual: String?=null) {
        if(wifi.network==null) { message="Connect to Wi-Fi first";return }
        val serial=++epoch;stopDiscovery();state="finding";host="";code="";message="Looking for KigEyes…"
        fun candidate(address: String?) {
            if(address==null || !local(address)) return
            worker.execute {
                if(serial!=epoch || host.isNotEmpty()) return@execute
                runCatching { call(address) }.onSuccess { reply ->
                    if(serial==epoch && reply.optString("product")=="KigEyes" && reply.optInt("protocol")==1) {
                        val name=reply.optString("name","KigEyes").take(48)
                        deviceName=name;prefs.edit().putString("deviceName",name).apply()
                        host=address;state="found";message="$name found · $address"
                        if(prefs.getString("approvedHost",null)==address || prefs.getStringSet("approvedAddresses",emptySet())!!.contains(address)) attach(serial)
                    }
                }
            }
        }
        candidate(manual)
        if(manual==null) {
            candidate(prefs.getString("approvedHost",null));candidate(wifi.gateway)
            main.post {
                if(serial!=epoch) return@post
                val discovery=object: NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(type: String) {}
                    override fun onDiscoveryStopped(type: String) {}
                    override fun onStartDiscoveryFailed(type: String,error: Int) {}
                    override fun onStopDiscoveryFailed(type: String,error: Int) {}
                    override fun onServiceLost(info: NsdServiceInfo) {}
                    override fun onServiceFound(info: NsdServiceInfo) {
                        if(serial!=epoch || !info.serviceType.contains("_kigeyes._tcp")) return
                        @Suppress("DEPRECATION")
                        nsd.resolveService(info,object: NsdManager.ResolveListener {
                            override fun onResolveFailed(i: NsdServiceInfo,error: Int) {}
                            override fun onServiceResolved(i: NsdServiceInfo) { if(serial==epoch) candidate(i.host?.hostAddress) }
                        })
                    }
                }
                listener=discovery
                runCatching {
                    if(Build.VERSION.SDK_INT>=33) nsd.discoverServices("_kigeyes._tcp.",NsdManager.PROTOCOL_DNS_SD,wifi.network,executor,discovery)
                    else nsd.discoverServices("_kigeyes._tcp.",NsdManager.PROTOCOL_DNS_SD,discovery)
                }
            }
        }
        main.postDelayed({ if(serial==epoch) {
            stopDiscovery()
            if(host.isEmpty()) { state="idle";message="No KigEyes found. Check its Wi-Fi or enter its address." }
        } },12000)
    }
    private fun payload(op: String)=JSONObject().put("op",op).put("key",key).put("stream_token",streamToken())
    fun provisioningPayload()=JSONObject().put("key",key).put("slot",preferredSlot)
    fun provisionedGateway(address: String) {
        require(local(address)) { "Invalid hotspot address" }
        prefs.edit().putStringSet("approvedAddresses",prefs.getStringSet("approvedAddresses",emptySet())!!.toSet()+address).apply()
    }
    private fun attach(serial: Int) {
        runCatching { call(host,payload("attach")) }.onSuccess { if(serial==epoch) consume(it) }
            .onFailure { if(serial==epoch) { state="found";message=it.message ?: "Could not attach camera" } }
    }
    fun request(cameraSlot: Int) {
        require(cameraSlot in 1..2)
        if(host.isEmpty()) { message="Find KigEyes first";return }
        selectSlot(cameraSlot);val previous=state;val serial=++epoch;state="requesting";message="Requesting Camera $cameraSlot…"
        worker.execute {
            runCatching { call(host,payload("request").put("name",Build.MODEL).put("slot",cameraSlot)) }
                .onSuccess { if(serial==epoch) { waitingUntil=System.currentTimeMillis()+120000;consume(it) } }
                .onFailure { if(serial==epoch) { state=if(previous=="paired") "paired" else "found";message=it.message ?: "Pairing failed" } }
        }
    }
    private fun consume(reply: JSONObject) {
        when(reply.optString("state")) {
            "waiting" -> { state="waiting";code=reply.getString("code");message="Approve this code in the glasses" }
            "paired" -> {
                state="paired";code="";slot=reply.getInt("slot");message="Paired · Camera $slot"
                preferredSlot=slot;prefs.edit().putString("approvedHost",host).putInt("assignedSlot",slot).putInt("preferredSlot",slot).apply()
            }
            else -> { state="found";code="";message="Pairing not approved. Request again when ready." }
        }
    }
    private fun stopDiscovery() { listener?.let { runCatching { nsd.stopServiceDiscovery(it) } };listener=null }
    fun close() { epoch++;main.removeCallbacksAndMessages(null);stopDiscovery();worker.shutdownNow() }
}
