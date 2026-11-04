package com.genymobile.scrcpy;

/** Requested awake policy, independent of Android APIs and phone timeout settings. */
public final class AwakePolicy {
    private boolean connected;
    private boolean streaming;
    private boolean standbyOverride;
    public synchronized void connected(boolean value) { connected=value; }
    public synchronized void streaming(boolean value) { streaming=value; }
    public synchronized void standbyOverride(boolean value) { standbyOverride=value; }
    public synchronized boolean requested() { return connected && (streaming || standbyOverride); }
}
