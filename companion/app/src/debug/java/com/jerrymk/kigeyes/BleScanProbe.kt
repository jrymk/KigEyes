package com.jerrymk.kigeyes

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import org.json.JSONObject
import java.io.File

/** Authorized ADB diagnostic only: never connects, pairs or stores nearby identities. */
internal object BleScanProbe {
    private var running=false
    @SuppressLint("MissingPermission")
    fun run(context: Context) {
        if(running)return
        running=true
        val report=JSONObject()
        val output=File(context.filesDir,"ble-scan-probe.json")
        val handler=Handler(Looper.getMainLooper())
        val scanner=context.getSystemService(BluetoothManager::class.java).adapter?.bluetoothLeScanner
        val callbacks=mutableListOf<ScanCallback>()
        for(filtered in listOf(false,true)) {
            val label=if(filtered)"filtered" else "unfiltered"
            val counts=JSONObject().put("results",0).put("serviceMatches",0).put("nameMatches",0)
            report.put(label,counts)
            val callback=object: ScanCallback() {
                override fun onScanResult(type: Int,result: ScanResult) { handler.post {
                    counts.put("results",counts.getInt("results")+1)
                    val record=result.scanRecord
                    if(record?.serviceUuids?.contains(ParcelUuid(BleProvisioner.SERVICE))==true)
                        counts.put("serviceMatches",counts.getInt("serviceMatches")+1)
                    if(record?.deviceName=="KigEyes")counts.put("nameMatches",counts.getInt("nameMatches")+1)
                } }
                override fun onScanFailed(error: Int) { handler.post { counts.put("error",error) } }
            }
            callbacks.add(callback)
            try {
                checkNotNull(scanner)
                val filters=if(filtered)listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(BleProvisioner.SERVICE)).build()) else emptyList()
                scanner.startScan(filters,ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),callback)
            } catch(e: Exception) { counts.put("exception",e.javaClass.simpleName) }
        }
        output.writeText(JSONObject().put("running",true).toString())
        handler.postDelayed({
            callbacks.forEach { runCatching { scanner?.stopScan(it) } }
            output.writeText(report.put("running",false).toString());running=false
        },12000)
    }
}
