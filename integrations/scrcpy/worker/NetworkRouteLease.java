package com.genymobile.scrcpy;

/** A suspended app cannot renew a route. A dead/stale owner loses its capability. */
final class NetworkRouteLease {
    private static final long TTL_MS=15000;
    private long handle;
    private long expires;
    synchronized void update(long value,long now) {
        if(value<0)throw new IllegalArgumentException("Invalid network handle");
        handle=value;expires=now+TTL_MS;
    }
    synchronized long current(long now) { return now<expires ? handle : 0; }
    synchronized void clear() { handle=0;expires=0; }
}
