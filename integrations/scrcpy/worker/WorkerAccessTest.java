package com.genymobile.scrcpy;
public final class WorkerAccessTest {
    public static void main(String[] args) {
        if (!WorkerAccess.allowed(10234,10234) || WorkerAccess.allowed(10234,10235)
                || WorkerAccess.allowed(-1,-1) || WorkerAccess.allowed(2000,2000)
                || WorkerAccess.allowed(10234,2000)) throw new AssertionError("Companion UID boundary");
        System.out.println("Companion UID boundary passed");
    }
}
