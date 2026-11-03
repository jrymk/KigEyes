package com.jerrymk.kigeyes

import android.os.*

/** Read-only route capability. WifiLink remains the sole owner of the network request. */
internal object WorkerNetworkBridge: Binder() {
    private const val DESCRIPTOR="com.jerrymk.kigeyes.worker.network.v1"
    @Volatile var wifi: WifiLink?=null
    fun selectedHandle(): Long {
        val owner=wifi
        return if(owner?.localAddress?.startsWith("10.42.0.")==true) owner.network?.networkHandle ?: 0L else 0L
    }
    override fun onTransact(code: Int,data: Parcel,reply: Parcel?,flags: Int): Boolean {
        if(getCallingUid()!=2000)throw SecurityException("Only the authorized shell worker may query the route")
        if(reply==null || data.dataSize()>512 || code!=IBinder.FIRST_CALL_TRANSACTION)return false
        data.enforceInterface(DESCRIPTOR)
        require(data.dataAvail()==0)
        reply.writeNoException();reply.writeLong(selectedHandle())
        return true
    }
}
