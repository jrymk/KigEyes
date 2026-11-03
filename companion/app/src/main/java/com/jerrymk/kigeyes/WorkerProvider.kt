package com.jerrymk.kigeyes

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.*

/** A shell-only Binder handoff; ordinary apps cannot attach or obtain the worker. */
class WorkerProvider: ContentProvider() {
    override fun onCreate()=true
    override fun call(method: String,arg: String?,extras: Bundle?): Bundle {
        if(Binder.getCallingUid()!=2000)throw SecurityException("Only the authorized shell worker may attach")
        if(method=="install_request")return Bundle().apply { putString("request",WorkerInstallRequest.current()) }
        if(method=="install_status") {
            WorkerInstallRequest.report(arg,extras?.getString("stage"));return Bundle.EMPTY
        }
        require(method=="attach")
        val endpoint=extras?.getBinder("worker") ?: throw IllegalArgumentException("Missing worker")
        endpoint.linkToDeath({ if(worker===endpoint)worker=null },0)
        worker=endpoint
        if(!endpoint.isBinderAlive)worker=null
        return Bundle().apply { putBinder("app",WorkerNetworkBridge) }
    }
    override fun query(uri: Uri,projection: Array<out String>?,selection: String?,selectionArgs: Array<out String>?,sortOrder: String?): Cursor?=throw UnsupportedOperationException()
    override fun getType(uri: Uri): String?=null
    override fun insert(uri: Uri,values: ContentValues?): Uri?=throw UnsupportedOperationException()
    override fun delete(uri: Uri,selection: String?,selectionArgs: Array<out String>?): Int=throw UnsupportedOperationException()
    override fun update(uri: Uri,values: ContentValues?,selection: String?,selectionArgs: Array<out String>?): Int=throw UnsupportedOperationException()
    companion object {
        @Volatile internal var worker: IBinder?=null
    }
}
