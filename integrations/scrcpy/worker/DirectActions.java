package com.genymobile.scrcpy;

/** Explicit capabilities, never a remote shell or caller-selected intent/package. */
public final class DirectActions {
    private static final String PACKAGE = "com.jerrymk.kigeyes";
    private DirectActions() { }
    public static String[] arguments(String action) {
        switch (action) {
            case "photo_camera": case "video_camera":
                return new String[] {"/system/bin/am","start","-a","android.media.action."+
                    (action.equals("photo_camera")?"STILL_IMAGE_CAMERA":"VIDEO_CAMERA"),"-p","com.google.android.GoogleCamera"};
            case "shield_setup": return new String[] {"/system/bin/am","start","-a",
                "android.settings.action.MANAGE_OVERLAY_PERMISSION","-d","package:"+PACKAGE};
            case "shield_lock": return new String[] {"/system/bin/am","start-foreground-service","-n",PACKAGE+"/.TouchShieldService","-a","lock"};
            case "shield_status": case "shield_unlock": case "shield_permit": case "shield_restore": case "shield_renew":
                return new String[] {"/system/bin/am","broadcast","--receiver-foreground","-n",PACKAGE+"/.TouchShieldReceiver",
                    "--es","command",action.substring(7)};
            default: throw new IllegalArgumentException("Unsupported phone operation");
        }
    }
}
