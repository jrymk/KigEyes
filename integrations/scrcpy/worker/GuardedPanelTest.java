package com.genymobile.scrcpy.control;
import java.util.*;
public final class GuardedPanelTest {
    static void check(boolean v,String m) { if(!v)throw new AssertionError(m); }
    static final class Fake implements GuardedPanel.Platform {
        final List<String> calls=new ArrayList<>();boolean locked,failPrepare,failRestore;
        public void prepare() throws Exception { calls.add("prepare");if(failPrepare)throw new Exception(); }
        public boolean lockAndVerify() { calls.add("lock");return locked; }
        public void restorePolicy() throws Exception { calls.add("restore");if(failRestore)throw new Exception(); }
    }
    public static void main(String[] args) {
        Fake f=new Fake();GuardedPanel p=new GuardedPanel(f);
        check(p.beforeRestore() && f.calls.isEmpty(),"ordinary panel unchanged");
        check(p.arm() && p.arm() && f.calls.size()==1,"arm idempotent");
        check(!p.beforeRestore() && p.armed() && !f.calls.contains("restore"),"fail closed until locked");
        f.locked=true;check(p.beforeRestore() && !p.armed(),"locked restore");
        check(f.calls.equals(Arrays.asList("prepare","lock","lock","restore")),"lock before restoring timeout");
        f.failPrepare=true;check(!p.arm() && !p.armed(),"failed capability never arms");
        f.failPrepare=false;f.failRestore=true;p.arm();check(p.beforeRestore(),"locked phone can wake despite policy restore failure");
        System.out.println("Guarded panel ordering tests passed");
    }
}
