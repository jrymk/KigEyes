package com.genymobile.scrcpy.wrappers;

import android.os.Bundle;
import android.os.IInterface;
import android.os.SystemClock;
import android.system.Os;
import com.genymobile.scrcpy.control.GuardedPanel;
import com.genymobile.scrcpy.util.Settings;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONObject;

/** Shell-only Android lock boundary. Never changes credentials or unlocks the phone. */
public final class GuardPlatform implements GuardedPanel.Platform {
    private static final String KEY="lock_screen_lock_after_timeout";
    private final IInterface window=ServiceManager.getService("window","android.view.IWindowManager");
    private final File journal=new File(new File(System.getProperty("java.class.path").split(File.pathSeparator)[0]).getParentFile(),"guard-timeout.json");
    private boolean locked() throws Exception { return (Boolean)window.getClass().getMethod("isKeyguardLocked").invoke(window); }
    @Override public void prepare() throws Exception {
        // This worker currently bootstraps the primary phone user only. Do not silently
        // apply another user's lock policy if Android switched users.
        IInterface activity=ServiceManager.getService("activity","android.app.IActivityManager");
        Object user=activity.getClass().getMethod("getCurrentUser").invoke(activity);
        int userId=user.getClass().getField("id").getInt(user);
        if(userId!=0 || !(Boolean)window.getClass().getMethod("isKeyguardSecure",int.class).invoke(window,userId) || locked())
            throw new IllegalStateException("An unlocked phone with a PIN or password is required");
        window.getClass().getMethod("lockNow",Bundle.class); // Verify API before darkening anything.
        restorePolicy();
        String old=Settings.getValue(Settings.TABLE_SECURE,KEY);
        JSONObject value=new JSONObject().put("version",1).put("previous",old==null?JSONObject.NULL:old);
        File tmp=new File(journal.getPath()+".new");Files.write(tmp.toPath(),value.toString().getBytes(StandardCharsets.UTF_8));Os.chmod(tmp.getPath(),0600);
        Files.move(tmp.toPath(),journal.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        try {
            // Native keyguard handles the physical Power sleep immediately. The watcher
            // additionally locks before every software/lease restore; it is not the sole gate.
            Settings.putValue(Settings.TABLE_SECURE,KEY,"0");
            if(!"0".equals(Settings.getValue(Settings.TABLE_SECURE,KEY)))throw new IllegalStateException("Lock policy unavailable");
        } catch(Exception failure) { restorePolicy();throw failure; }
    }
    @Override public boolean lockAndVerify() throws Exception {
        window.getClass().getMethod("lockNow",Bundle.class).invoke(window,new Object[] {null});
        long deadline=SystemClock.uptimeMillis()+1500;
        while(SystemClock.uptimeMillis()<deadline) {
            if(locked()) {
                if(!com.genymobile.scrcpy.device.Device.isScreenOn(0))
                    com.genymobile.scrcpy.device.Device.pressReleaseKeycode(android.view.KeyEvent.KEYCODE_WAKEUP,0,0);
                Thread.sleep(100); // Let keyguard submit its first frame before a forced panel restore.
                return locked();
            }
            Thread.sleep(20);
        }
        return false;
    }
    @Override public void restorePolicy() throws Exception {
        if(!journal.isFile())return;
        JSONObject saved=new JSONObject(new String(Files.readAllBytes(journal.toPath()),StandardCharsets.UTF_8));
        if(saved.getInt("version")!=1)throw new IllegalStateException("Unsupported lock policy backup");
        // Do not overwrite a setting changed by the user while this mode was running.
        if("0".equals(Settings.getValue(Settings.TABLE_SECURE,KEY)))
            Settings.putValue(Settings.TABLE_SECURE,KEY,saved.isNull("previous")?null:saved.getString("previous"));
        Files.delete(journal.toPath());
    }
}
