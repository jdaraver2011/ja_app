package com.example.ja;

public final class JaBlockerLifecycleStateTest {
    private static void check(boolean value,String message) {
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        JaBlockerLifecycleState state = new JaBlockerLifecycleState();
        long initial = state.request("ublock");
        check(state.isCurrent(initial,"ublock"),"Initial selection rejected");
        check(!state.hasStarted("uBlock0@raymondhill.net"),"Install implied readiness");
        state.ready("uBlock0@raymondhill.net");
        check(state.hasStarted("uBlock0@raymondhill.net"),"Ready event lost");
        state.stopped("uBlock0@raymondhill.net");
        check(!state.hasStarted("uBlock0@raymondhill.net"),"Disabled extension stayed ready");
        long off = state.request("off");
        check(!state.isCurrent(initial,"ublock"),"Stale enable callback accepted after Off");
        check(state.isCurrent(off,"off"),"Off selection lost");
        long reenable=state.request("ublock");
        check(reenable!=initial,"Reenable reused old generation");
        check(!state.isCurrent(initial,"ublock"),"Old callback accepted after Off -> uBlock");
        state.starting("uBlock0@raymondhill.net");
        check(!state.hasStarted("uBlock0@raymondhill.net"),"Starting was treated as started");
        state.ready("uBlock0@raymondhill.net");
        state.stopped("uBlock0@raymondhill.net");
        check(!state.hasStarted("uBlock0@raymondhill.net"),"Uninstalled extension stayed ready");
        long joined=state.request("ublock");
        check(joined==reenable,"Same-mode player prepare superseded the Settings request");
        state.ready("adguardadblocker@adguard.com");
        check(!state.hasStarted("uBlock0@raymondhill.net"),"Readiness leaked between extensions");
        System.out.println("PASS: lifecycle invalidation, stale callbacks, Off -> uBlock generations and independent engines");
    }
}
