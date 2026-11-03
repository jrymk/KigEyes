package com.jerrymk.kigeyes

import android.os.IBinder
import android.os.Parcel
import org.json.JSONObject
import java.io.Closeable
import java.io.IOException

/** Binder is received only by the shell-protected provider, never over a network. */
internal class MirrorWorkerClient: Closeable {
    override fun close() { /* The app process owns the Binder handle. */ }
    fun request(action: String="status",allowed: Boolean?=null): MirrorWorkerState {
        require(action in setOf("status","set_allowed","stop_worker","screen_off"))
        val worker=WorkerProvider.worker ?: throw IOException("Worker unavailable")
        val request=JSONObject().put("action",action).put("network_handle",WorkerNetworkBridge.selectedHandle())
        allowed?.let { request.put("allowed",it) }
        val data=Parcel.obtain();val reply=Parcel.obtain()
        try {
            data.writeInterfaceToken("com.jerrymk.kigeyes.worker.v1");data.writeString(request.toString())
            if(!worker.transact(IBinder.FIRST_CALL_TRANSACTION,data,reply,0))throw IOException("Unsupported worker")
            reply.readException()
            val text=reply.readString() ?: throw IOException("Empty worker response")
            if(text.length>4096)throw IOException("Invalid worker response")
            val response=JSONObject(text)
            if(!response.optBoolean("ok") || response.optInt("version")!=1)throw IOException("Worker rejected request")
            return MirrorWorkerState(true,response.getBoolean("allowed"),response.getBoolean("connected"),
                response.getBoolean("streaming"),response.getString("worker_version"),response.optBoolean("stopping"),networkRoute=response.optString("network_route"),screenOffSupported=response.optBoolean("screen_off_supported"),screenGuard=response.optInt("screen_guard"))
        } finally { data.recycle();reply.recycle() }
    }
}
