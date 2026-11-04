package com.genymobile.scrcpy;

/** UID is provisioned from PackageManager by the authorized ADB installer. */
final class WorkerAccess {
    static boolean allowed(int installedAppUid, int peerUid) {
        return installedAppUid>=10000 && installedAppUid==peerUid;
    }
}
