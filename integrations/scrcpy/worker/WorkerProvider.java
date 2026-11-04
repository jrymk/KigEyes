package com.genymobile.scrcpy.wrappers;

import android.content.AttributionSource;
import android.content.pm.ApplicationInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;
import com.genymobile.scrcpy.FakeContext;
import java.lang.reflect.Method;

/** Balanced provider lease; no generic provider name or operation is accepted. */
public final class WorkerProvider {
    private static final String PACKAGE="com.jerrymk.kigeyes";
    private static final String AUTHORITY=PACKAGE+".worker";
    public static IBinder attach(int uid,IBinder worker) throws Exception {
        ApplicationInfo info=FakeContext.get().getPackageManager().getApplicationInfo(PACKAGE,0);
        if(info.uid!=uid || (info.flags & ApplicationInfo.FLAG_STOPPED)!=0)return null;
        int user=uid/100000;
        IInterface manager=ServiceManager.getService("activity","android.app.IActivityManager");
        IBinder token=new Binder();
        Object holder=manager.getClass().getMethod("getContentProviderExternal",String.class,int.class,IBinder.class,String.class)
            .invoke(manager,AUTHORITY,user,token,null);
        if(holder==null)return null;
        try {
            Object provider=holder.getClass().getField("provider").get(holder);
            Bundle extras=new Bundle();extras.putBinder("worker",worker);
            Method call=provider.getClass().getMethod("call",AttributionSource.class,String.class,String.class,String.class,Bundle.class);
            Bundle result=(Bundle)call.invoke(provider,FakeContext.get().getAttributionSource(),AUTHORITY,"attach",null,extras);
            return result==null ? null : result.getBinder("app");
        } finally {
            manager.getClass().getMethod("removeContentProviderExternalAsUser",String.class,IBinder.class,int.class)
                .invoke(manager,AUTHORITY,token,user);
        }
    }
}
