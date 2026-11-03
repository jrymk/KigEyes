package com.jerrymk.kigeyes

import org.junit.Assert.*
import org.junit.Test
import java.net.Socket

class RtspTest {
    @Test fun sessionRequiresTokenAndRejectsUnsupportedTransport() {
        RtspServer("127.0.0.1","test-token",port=0) {}.use { server ->
            server.sps=byteArrayOf(0x67,0x42,0,0x1f); server.pps=byteArrayOf(0x68,1)
            Socket("127.0.0.1",server.port).use { socket ->
                socket.soTimeout=2000
                val input=socket.getInputStream().bufferedReader()
                fun request(method: String,path: String,header: String=""): Pair<String,String> {
                    socket.getOutputStream().write("$method rtsp://127.0.0.1:${server.port}/$path RTSP/1.0\r\nCSeq: 1\r\n$header\r\n".toByteArray())
                    val status=input.readLine(); var length=0
                    while(true) { val line=input.readLine(); if(line.isEmpty()) break; if(line.startsWith("Content-Length:")) length=line.substringAfter(':').trim().toInt() }
                    val body=CharArray(length); var read=0
                    while(read<length) { val n=input.read(body,read,length-read); check(n>0); read+=n }
                    return status to String(body)
                }
                assertTrue(request("DESCRIBE","live/wrong").first.contains("404"))
                val describe=request("DESCRIBE","live/test-token")
                assertTrue(describe.first.contains("200")); assertTrue(describe.second.contains("H264/90000"))
                assertTrue(request("SETUP","live/test-token/trackID=0","Transport: RTP/AVP;unicast;client_port=5000-5001\r\n").first.contains("461"))
                assertTrue(request("SETUP","live/test-token/trackID=0","Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n").first.contains("200"))
                assertTrue(request("GET_PARAMETER","live/test-token/").first.contains("200"))
                assertTrue(request("TEARDOWN","live/test-token").first.contains("200"))
            }
        }
    }
}
