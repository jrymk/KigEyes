package com.jerrymk.kigeyes

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.*
import android.os.*
import org.json.JSONObject
import java.util.UUID

/** Platform numeric-comparison bonding; credentials are protected GATT reads. */
@SuppressLint("MissingPermission")
class BleProvisioner(private val context: Context,private val payload: ()->JSONObject,
                     private val credentials: (JSONObject,String)->Unit,private val finished: (Boolean)->Unit) {
    companion object {
        val SERVICE=UUID.fromString("8d53dc1d-1db7-4cd3-868b-8a527460aa01")
        val HELLO=UUID.fromString("8d53dc1d-1db7-4cd3-868b-8a527460aa02")
        val SETUP=UUID.fromString("8d53dc1d-1db7-4cd3-868b-8a527460aa03")
        val CREDENTIALS=UUID.fromString("8d53dc1d-1db7-4cd3-868b-8a527460aa04")
        private const val CONFIRM_INSTRUCTIONS="Check that the codes on the phone and glasses match. Approve on the phone, then briefly press the KigEyes power button once to approve in the glasses."
    }
    private val adapter=context.getSystemService(BluetoothManager::class.java).adapter
    private val main=Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt?=null
    private var scanner: BluetoothLeScanner?=null
    private var scan: ScanCallback?=null
    private var receiverRegistered=false
    private var setupSent=false
    private var servicesReady=false
    private var mtuReady=false
    private var helloSent=false
    private var helloComplete=false
    private var serial=0
    @Volatile var busy=false;private set
    @Volatile var failed=false;private set
    @Volatile var message="Find KigEyes nearby with Bluetooth";private set
    private val receiver=object: BroadcastReceiver() {
        override fun onReceive(context: Context,intent: Intent) {
            @Suppress("DEPRECATION") val device=intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
            if(device.address!=gatt?.device?.address || !busy) return
            when(intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE,BluetoothDevice.BOND_NONE)) {
                BluetoothDevice.BOND_BONDED -> sendSetup()
                BluetoothDevice.BOND_BONDING -> message=CONFIRM_INSTRUCTIONS
                BluetoothDevice.BOND_NONE -> if(intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE,0)==BluetoothDevice.BOND_BONDING) fail("Bluetooth pairing declined or failed. Try again.")
            }
        }
    }
    fun start() {
        cancel();busy=true;setupSent=false;servicesReady=false;mtuReady=false;helloSent=false;helloComplete=false;val attempt=++serial
        if(adapter==null || !adapter.isEnabled) { fail("Turn on Bluetooth, then try again");return }
        message="Looking for KigEyes nearby…"
        if(Build.VERSION.SDK_INT>=33) context.registerReceiver(receiver,IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),Context.RECEIVER_EXPORTED)
        else context.registerReceiver(receiver,IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
        receiverRegistered=true
        scanner=adapter.bluetoothLeScanner
        scan=object: ScanCallback() {
            override fun onScanResult(type: Int,result: ScanResult) {
                main.post {
                    if(!busy || attempt!=serial || gatt!=null) return@post
                    stopScan();message="Connecting to nearby KigEyes…"
                    gatt=result.device.connectGatt(context,false,callback,BluetoothDevice.TRANSPORT_LE)
                }
            }
            override fun onScanFailed(error: Int) { main.post { if(attempt==serial) fail("Bluetooth scan failed ($error). Try again.") } }
        }
        try { scanner?.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),scan) }
        catch(e: Exception) { fail(e.message ?: "Cannot scan Bluetooth");return }
        main.postDelayed({ if(attempt==serial && busy && gatt==null) fail("No KigEyes found nearby. Check its power and Bluetooth.") },15000)
        main.postDelayed({ if(attempt==serial && busy) fail("Setup timed out. Try again when both devices are ready.") },120000)
    }
    private val callback=object: BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt,status: Int,newState: Int) { main.post {
            if(g!==gatt || !busy)return@post
            if(status!=BluetoothGatt.GATT_SUCCESS || newState==BluetoothProfile.STATE_DISCONNECTED) { fail("Bluetooth setup disconnected. Try again.");return@post }
            if(newState==BluetoothProfile.STATE_CONNECTED && !g.discoverServices()) fail("Cannot read setup service")
        } }
        override fun onServicesDiscovered(g: BluetoothGatt,status: Int) { main.post {
            if(g!==gatt || !busy)return@post
            if(status!=BluetoothGatt.GATT_SUCCESS || g.getService(SERVICE)==null) { fail("KigEyes setup service unavailable");return@post }
            servicesReady=true
            if(mtuReady) sendHello() else if(!g.requestMtu(517)) fail("Cannot negotiate Bluetooth setup")
        } }
        override fun onMtuChanged(g: BluetoothGatt,mtu: Int,status: Int) { main.post {
            if(g!==gatt || !busy)return@post
            if(status!=BluetoothGatt.GATT_SUCCESS || mtu<128) { fail("Bluetooth setup needs a larger packet size");return@post }
            mtuReady=true;sendHello()
        } }
        override fun onCharacteristicWrite(g: BluetoothGatt,c: BluetoothGattCharacteristic,status: Int) { main.post {
            if(g!==gatt || !busy)return@post
            if(status!=BluetoothGatt.GATT_SUCCESS) { fail("Secure Bluetooth setup failed ($status). Retry pairing.");return@post }
            when(c.uuid) {
                HELLO -> {
                    helloComplete=true
                    if(g.device.bondState==BluetoothDevice.BOND_BONDED) sendSetup()
                    else { message=CONFIRM_INSTRUCTIONS;if(!g.device.createBond()) fail("Could not start Bluetooth pairing") }
                }
                SETUP -> {
                    message="Receiving Wi-Fi details securely…"
                    val read=g.getService(SERVICE)?.getCharacteristic(CREDENTIALS)
                    if(read==null || !g.readCharacteristic(read)) fail("Cannot retrieve Wi-Fi details")
                }
            }
        } }
        override fun onCharacteristicRead(g: BluetoothGatt,c: BluetoothGattCharacteristic,value: ByteArray,status: Int) { received(g,c,value,status) }
        @Deprecated("Legacy Android callback")
        override fun onCharacteristicRead(g: BluetoothGatt,c: BluetoothGattCharacteristic,status: Int) {
            @Suppress("DEPRECATION") val value=c.value ?: byteArrayOf();received(g,c,value,status)
        }
    }
    private fun received(g: BluetoothGatt,c: BluetoothGattCharacteristic,value: ByteArray,status: Int) { main.post {
        if(g!==gatt || !busy || c.uuid!=CREDENTIALS)return@post
        if(status!=BluetoothGatt.GATT_SUCCESS) { fail("Wi-Fi details require authenticated Bluetooth pairing ($status)");return@post }
        try {
            check(value.size<=1024);val data=JSONObject(String(value))
            check(data.getString("ssid").length in 1..32 && data.getString("password").length in 8..63)
            message="Paired securely · joining KigEyes Wi-Fi…";busy=false;val peer=g.device.address;cleanup();credentials(data,peer);finished(true)
        } catch(e: Exception) { fail(e.message ?: "Invalid setup response") }
    } }
    private fun sendSetup() {
        val device=gatt?.device ?: return
        if(setupSent || !busy || !servicesReady || !mtuReady || !helloComplete || device.bondState!=BluetoothDevice.BOND_BONDED)return
        setupSent=true;message="Registering this phone securely…";write(SETUP,payload())
    }
    private fun sendHello() {
        // Android may negotiate MTU automatically before service discovery finishes.
        if(!servicesReady || !mtuReady || helloSent || !busy)return
        helloSent=true;write(HELLO,JSONObject().put("name",Build.MODEL).put("slot",payload().optInt("slot",1)))
    }
    private fun write(uuid: UUID,data: JSONObject) {
        val g=gatt ?: return;val c=g.getService(SERVICE)?.getCharacteristic(uuid) ?: return fail("Setup characteristic missing")
        val bytes=data.toString().toByteArray()
        val ok=if(Build.VERSION.SDK_INT>=33) g.writeCharacteristic(c,bytes,BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)==BluetoothStatusCodes.SUCCESS
        else { @Suppress("DEPRECATION") c.value=bytes;@Suppress("DEPRECATION") g.writeCharacteristic(c) }
        if(!ok) fail("Bluetooth setup busy. Try again.")
    }
    private fun stopScan() { scan?.let { runCatching { scanner?.stopScan(it) } };scan=null;scanner=null }
    private fun cleanup() {
        stopScan();gatt?.let { runCatching { it.disconnect();it.close() } };gatt=null
        if(receiverRegistered) { runCatching { context.unregisterReceiver(receiver) };receiverRegistered=false }
    }
    private fun fail(reason: String) { failed=true;message=reason;busy=false;++serial;cleanup();finished(false) }
    fun cancel() { ++serial;busy=false;failed=false;main.removeCallbacksAndMessages(null);cleanup() }
}
