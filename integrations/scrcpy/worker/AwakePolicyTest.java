package com.genymobile.scrcpy;

public final class AwakePolicyTest {
    private static void check(boolean value) { if (!value) throw new AssertionError("Unexpected awake policy"); }
    public static void main(String[] args) {
        AwakePolicy p=new AwakePolicy();check(!p.requested());
        p.connected(true);check(!p.requested());
        p.streaming(true);check(p.requested());
        p.streaming(false);check(!p.requested());
        p.standbyOverride(true);check(p.requested());
        p.connected(false);check(!p.requested());
        p.connected(true);check(p.requested());
        p.standbyOverride(false);check(!p.requested());
        System.out.println("AwakePolicy: idle, streaming, standby override and disconnect passed");
    }
}
