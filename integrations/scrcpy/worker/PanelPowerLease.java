package com.genymobile.scrcpy.control;

/** Session-local permission to keep a physical panel dark. Controller owns locking. */
final class PanelPowerLease {
    static final long TIMEOUT_MS = 10_000;
    private long deadline = -1;

    void arm(long now) {
        deadline = now + TIMEOUT_MS;
    }

    void clear() {
        deadline = -1;
    }

    boolean armed() {
        return deadline >= 0;
    }

    void renew(long now) {
        // Buffered/late heartbeats must not revive an expired lease or re-darken
        // a restored panel. Only a new explicit panel-off command arms it.
        if (armed() && now < deadline) {
            deadline = now + TIMEOUT_MS;
        }
    }

    boolean expired(long now) {
        return armed() && now >= deadline;
    }
}
