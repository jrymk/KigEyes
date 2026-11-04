package com.genymobile.scrcpy;
public final class NetworkRouteLeaseTest {
    private static void check(boolean value) { if(!value)throw new AssertionError(); }
    public static void main(String[] args) {
        NetworkRouteLease route=new NetworkRouteLease();
        check(route.current(0)==0);
        route.update(100,1000);check(route.current(15999)==100);check(route.current(16000)==0);
        route.update(100,20000);route.update(100,22000);check(route.current(36000)==100);
        route.update(200,23000);check(route.current(23000)==200);
        route.update(0,24000);check(route.current(24000)==0);
        route.update(200,25000);route.clear();check(route.current(25001)==0);
        try { route.update(-1,26000);throw new AssertionError(); } catch(IllegalArgumentException expected) { }
        System.out.println("Network route lease tests passed");
    }
}
