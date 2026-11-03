package com.jerrymk.kigeyes

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.*

/** Presence is only a hint. WPA and the pinned worker TLS identity still authenticate peers. */
internal class BlePresence(private val context: Context,private val nearby: ()->Unit) {
    private val adapter=context.getSystemService(BluetoothManager::class.java).adapter
    private val main=Handler(Looper.getMainLooper())
    private var scanner: BluetoothLeScanner?=null
    private var callback: ScanCallback?=null
    private var address: String?=null
    private var retryAfter=0L
    var status="";private set

    @android.annotation.SuppressLint("MissingPermission")
    fun trusted(peer: String?): Boolean = peer!=null &&
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED &&
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED &&
        runCatching { adapter?.isEnabled==true && adapter.bondedDevices.any { it.address==peer } }.getOrDefault(false)

    @android.annotation.SuppressLint("MissingPermission")
    fun connectionLabel(peer: String?): String = when {
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED->"Permission needed"
        runCatching { adapter?.isEnabled!=true }.getOrDefault(true)->"Off"
        peer==null->"Not paired"
        runCatching { adapter?.bondedDevices?.any { it.address==peer }==true }.getOrDefault(false)->"Paired"
        else->"Not paired"
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun watch(peer: String?) {
        if(peer==null || !trusted(peer)) { stop();return }
        if(callback!=null && address==peer || SystemClock.elapsedRealtime()<retryAfter)return
        stop();address=peer
        val scan=object: ScanCallback() {
            override fun onScanResult(type: Int,result: ScanResult) { main.post {
                if(callback===this && result.device.address==peer)nearby()
            } }
            override fun onScanFailed(error: Int) { main.post {
                if(callback===this) { stop();status="Bluetooth discovery unavailable · Connect to retry";retryAfter=SystemClock.elapsedRealtime()+60_000 }
            } }
        }
        callback=scan
        try {
            scanner=adapter?.bluetoothLeScanner ?: error("Bluetooth unavailable")
            scanner?.startScan(listOf(ScanFilter.Builder().setDeviceAddress(peer)
                .setServiceUuid(ParcelUuid(BleProvisioner.SERVICE)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build(),scan)
            status="Looking for your paired KigEyes nearby"
        } catch(_: Exception) { stop();status="Bluetooth discovery unavailable · Connect to retry";retryAfter=SystemClock.elapsedRealtime()+60_000 }
    }
    @android.annotation.SuppressLint("MissingPermission")
    fun stop() {
        callback?.let { runCatching { scanner?.stopScan(it) } }
        callback=null;scanner=null;address=null;status=""
    }
}
