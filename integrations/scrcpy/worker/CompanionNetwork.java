package com.genymobile.scrcpy;

import android.net.Network;
import java.net.Socket;

/** Uses an app-pushed lease; never synchronously calls a possibly frozen app. */
final class CompanionNetwork {
    static long bind(long handle,Socket socket,String host,int port) throws Exception {
        if(!host.equals("10.42.0.1") || port!=8561)return 0;
        if(handle!=0)Network.fromNetworkHandle(handle).bindSocket(socket);
        return handle;
    }
}
