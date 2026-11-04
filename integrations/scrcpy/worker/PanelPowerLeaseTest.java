package com.genymobile.scrcpy.control;

/** No Android runtime needed: deterministic monotonic-clock boundary tests. */
public final class PanelPowerLeaseTest {
    public static void main(String[] args) {
        PanelPowerLease lease = new PanelPowerLease();
        lease.renew(100);
        check(!lease.armed(), "heartbeat must not arm");
        lease.arm(100);
        check(!lease.expired(10_099), "must not expire early");
        check(lease.expired(10_100), "must expire at deadline");
        lease.renew(10_100);
        check(lease.expired(10_100), "late heartbeat must not revive");
        lease.clear();
        lease.renew(20_000);
        check(!lease.armed(), "heartbeat must not undo panel-on");
        lease.arm(30_000);
        lease.renew(32_000);
        check(!lease.expired(41_999), "live heartbeat extends lease");
        check(lease.expired(42_000), "lost heartbeat expires renewed lease");
        lease.clear();
        check(!lease.expired(Long.MAX_VALUE), "cleared lease stays clear");
        System.out.println("PanelPowerLease: 8 checks passed");
    }

    private static void check(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }
}
