package com.genymobile.scrcpy.control;

/** Ordering policy only: a guarded panel must never be restored before Android locks. */
public final class GuardedPanel {
    public interface Platform {
        void prepare() throws Exception;
        boolean lockAndVerify() throws Exception;
        void restorePolicy() throws Exception;
    }
    private final Platform platform;
    private boolean armed;
    public GuardedPanel(Platform platform) { this.platform=platform; }
    public synchronized boolean armed() { return armed; }
    public synchronized boolean arm() {
        if(armed)return true;
        try { platform.prepare();armed=true;return true; }
        catch(Exception failure) { return false; }
    }
    public synchronized boolean beforeRestore() {
        if(!armed)return true;
        try {
            if(!platform.lockAndVerify())return false;
            // Failure to restore the old timeout must not prevent a locked phone waking.
            try { platform.restorePolicy(); } catch(Exception ignored) { }
            armed=false;return true;
        } catch(Exception failure) { return false; }
    }
}
