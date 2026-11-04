package com.genymobile.scrcpy;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import java.io.Closeable;
import org.json.JSONObject;
import com.genymobile.scrcpy.wrappers.WorkerProvider;

/** Narrow Binder capability delivered only to the companion's shell-only provider. */
final class WorkerLocalControl implements Closeable {
    static final String DESCRIPTOR="com.jerrymk.kigeyes.worker.v1";
    interface Handler { JSONObject command(JSONObject request) throws Exception; }
    private final Thread thread;
    private volatile boolean closed;
    private volatile IBinder app;
    private final Binder endpoint;
    private final NetworkRouteLease route=new NetworkRouteLease();

    WorkerLocalControl(int appUid, Handler handler, Runnable stopWorker, Runnable networkChanged) {
        endpoint=new Binder() {
            @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags) throws RemoteException {
                if(!WorkerAccess.allowed(appUid,Binder.getCallingUid()))throw new SecurityException("Wrong companion UID");
                if(code!=IBinder.FIRST_CALL_TRANSACTION || data.dataSize()>8192 || reply==null)return false;
                data.enforceInterface(DESCRIPTOR);
                String message=data.readString();
                if(message==null || message.length()>4096 || data.dataAvail()!=0)return false;
                boolean stop=false;
                JSONObject response;
                try {
                    JSONObject request=new JSONObject(message);
                    if(request.has("network_handle"))route.update(request.getLong("network_handle"),android.os.SystemClock.elapsedRealtime());
                    long identity=Binder.clearCallingIdentity();
                    try { response=handler.command(request); }
                    finally { Binder.restoreCallingIdentity(identity); }
                    stop=response.optBoolean("ok") && request.optString("action").equals("stop_worker");
                } catch(Exception e) { response=new JSONObject(); }
                reply.writeNoException();reply.writeString(response.toString());
                if(stop) {
                    // A separate owned thread performs shutdown, never a Binder pool thread.
                    stopRequested=true;closed=true;thread.interrupt();
                }
                return true;
            }
        };
        thread=new Thread(() -> {
            long previousNetwork=0;
            while(!closed) {
                if(app==null && appUid>=10000) {
                    try {
                        IBinder peer=WorkerProvider.attach(appUid,endpoint);
                        if(peer!=null) {
                            app=peer;
                            peer.linkToDeath(new IBinder.DeathRecipient() {
                                @Override public void binderDied() { if(app==peer) { app=null;route.clear(); } }
                                @Override public void binderDied(IBinder who) { binderDied(); }
                            },0);
                            if(!peer.isBinderAlive())app=null;
                        }
                    } catch(Exception ignored) { app=null; }
                }
                long currentNetwork=route.current(android.os.SystemClock.elapsedRealtime());
                if(currentNetwork!=previousNetwork) { previousNetwork=currentNetwork;networkChanged.run(); }
                try { Thread.sleep(3000); }
                catch(InterruptedException e) { if(closed)break; }
            }
            // close() itself must not stop the worker during ordinary service cleanup.
            if(stopRequested)stopWorker.run();
        },"phone-companion-binder");thread.setDaemon(true);thread.start();
    }
    long bind(java.net.Socket socket,String host,int port) throws Exception {
        return CompanionNetwork.bind(route.current(android.os.SystemClock.elapsedRealtime()),socket,host,port);
    }
    private volatile boolean stopRequested;
    @Override public void close() { closed=true;thread.interrupt();app=null;route.clear(); }
}
