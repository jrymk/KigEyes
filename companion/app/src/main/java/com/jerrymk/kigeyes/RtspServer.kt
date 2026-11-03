package com.jerrymk.kigeyes

import java.io.*
import java.net.*
import java.nio.ByteBuffer
import java.util.Base64
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicLong

/** Small H.264 / RTP-over-RTSP server. Each receiver has a bounded independent queue. */
class RtspServer(private val address: String, val token: String, port: Int=8554, private val requestKey: () -> Unit) : Closeable {
    private val server = ServerSocket()
    private val clients = CopyOnWriteArrayList<Client>()
    private val executor = Executors.newCachedThreadPool()
    private val watchdog = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var running = true
    @Volatile var sps: ByteArray? = null
    @Volatile var pps: ByteArray? = null
    val dropped = AtomicLong()
    val connections get() = clients.count { it.playing }
    val port get() = server.localPort
    val url get() = "rtsp://$address:$port/live/$token"

    init {
        server.reuseAddress = true
        server.bind(InetSocketAddress(address, port), 4)
        executor.execute {
            while(running) try {
                val socket = server.accept()
                if(clients.size >= 4) { socket.close(); continue }
                val client = Client(socket); clients.add(client); executor.execute { client.readLoop() }
            } catch (_: IOException) { if(running) Thread.sleep(100) }
        }
        watchdog.scheduleWithFixedDelay({
            val now=System.nanoTime()
            clients.forEach { if(it.writingSince != 0L && now-it.writingSince > 2_000_000_000L) it.close() }
        }, 1, 1, TimeUnit.SECONDS)
    }
    fun offer(frame: EncodedFrame) { clients.forEach { it.offer(frame) } }
    override fun close() {
        running=false; runCatching { server.close() }; clients.forEach { it.close() }
        executor.shutdownNow(); watchdog.shutdownNow()
    }
    private inner class Client(private val socket: Socket) : Closeable {
        private val input = BufferedInputStream(socket.getInputStream())
        private val output = BufferedOutputStream(socket.getOutputStream(), 65536)
        private val outputLock = Any()
        private val queue = ArrayBlockingQueue<EncodedFrame>(6)
        private var waitingKey = true
        @Volatile var playing = false
        @Volatile private var alive = true
        @Volatile var writingSince = 0L
        private val session = UUID.randomUUID().toString().take(16)
        private var channel = 0
        private var sequence = (Math.random()*65535).toInt()
        private val ssrc = (Math.random()*Int.MAX_VALUE).toInt()
        private var packetCount=0; private var octetCount=0; private var lastReport=0L
        init { socket.tcpNoDelay=true; socket.soTimeout=60000; socket.sendBufferSize=65536 }
        @Synchronized fun offer(frame: EncodedFrame) {
            if(!playing || !alive) return
            if(waitingKey && !frame.key) return
            if(frame.key) waitingKey=false
            if(!queue.offer(frame)) {
                dropped.incrementAndGet(); queue.clear(); waitingKey=true; requestKey()
                if(frame.key) { queue.offer(frame); waitingKey=false }
            }
        }
        private fun line(): String? {
            val bytes=ByteArrayOutputStream()
            while(bytes.size()<8192) {
                val b=input.read(); if(b<0) return null
                if(b==10) return bytes.toString("UTF-8").trimEnd('\r')
                bytes.write(b)
            }
            throw IOException("Oversized request")
        }
        private fun discard(count: Int): Boolean {
            val scratch=ByteArray(minOf(count,4096)); var remaining=count
            while(remaining>0) { val n=input.read(scratch,0,minOf(remaining,scratch.size)); if(n<0) return false; remaining-=n }
            return true
        }
        fun readLoop() {
            try {
                while(alive) {
                    input.mark(1); val first=input.read(); if(first<0) break
                    if(first==36) { // Client RTCP on the interleaved control channel.
                        input.read(); val n=(input.read() shl 8) or input.read()
                        if(n<0 || n>65535) break
                        if(!discard(n)) break
                        continue
                    }
                    input.reset()
                    val request=line()?.split(' ') ?: break
                    if(request.size<3) break
                    val headers=mutableMapOf<String,String>()
                    var count=0
                    while(true) {
                        val l=line() ?: throw EOFException(); if(l.isEmpty()) break
                        if(++count>64) throw IOException("Too many headers")
                        val colon=l.indexOf(':'); if(colon>0) headers[l.substring(0,colon).lowercase()]=l.substring(colon+1).trim()
                    }
                    val length=headers["content-length"]?.toIntOrNull() ?: 0
                    if(length !in 0..8192) break
                    if(length>0 && !discard(length)) break
                    val cseq=headers["cseq"]?.toIntOrNull() ?: 0
                    val path=(runCatching { URI(request[1]).path }.getOrNull() ?: "").trimEnd('/')
                    if(request[0]!="OPTIONS" && path!="/live/$token" && path!="/live/$token/trackID=0") {
                        reply(cseq,404); continue
                    }
                    when(request[0]) {
                        "OPTIONS" -> reply(cseq,200,"Public: OPTIONS, DESCRIBE, SETUP, PLAY, GET_PARAMETER, TEARDOWN\r\n")
                        "DESCRIBE" -> {
                            val s=sps; val p=pps
                            if(s==null || p==null) { reply(cseq,503); continue }
                            val profile=s.drop(1).take(3).joinToString("") { "%02x".format(it.toInt() and 255) }
                            val body="v=0\r\no=- 0 0 IN IP4 $address\r\ns=KigEyes Camera\r\nc=IN IP4 $address\r\nt=0 0\r\na=control:*\r\nm=video 0 RTP/AVP 96\r\na=rtpmap:96 H264/90000\r\na=fmtp:96 packetization-mode=1;profile-level-id=$profile;sprop-parameter-sets=${Base64.getEncoder().encodeToString(s)},${Base64.getEncoder().encodeToString(p)}\r\na=control:trackID=0\r\n"
                            reply(cseq,200,"Content-Type: application/sdp\r\nContent-Base: $url/\r\n",body)
                        }
                        "SETUP" -> {
                            val transport=headers["transport"] ?: ""
                            if(!transport.uppercase().contains("RTP/AVP/TCP")) { reply(cseq,461); continue }
                            channel=Regex("interleaved=(\\d+)-\\d+").find(transport)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                            if(channel !in 0..254) { reply(cseq,461); continue }
                            reply(cseq,200,"Transport: RTP/AVP/TCP;unicast;interleaved=$channel-${channel+1};ssrc=${ssrc.toUInt().toString(16)}\r\nSession: $session;timeout=60\r\n")
                        }
                        "PLAY" -> {
                            reply(cseq,200,"Session: $session\r\nRange: npt=0.000-\r\n")
                            if(!playing) { playing=true; requestKey(); executor.execute { writeLoop() } }
                        }
                        "GET_PARAMETER" -> reply(cseq,200,"Session: $session\r\n")
                        "TEARDOWN" -> { reply(cseq,200); break }
                        else -> reply(cseq,405)
                    }
                }
            } catch(_: Exception) {} finally { close() }
        }
        private fun reply(cseq: Int, code: Int, headers: String="", body: String="") {
            val reason=when(code) {200->"OK";404->"Not Found";461->"Unsupported Transport";503->"Service Unavailable";else->"Method Not Allowed"}
            val bytes=body.toByteArray()
            synchronized(outputLock) {
                writingSince=System.nanoTime()
                try {
                    output.write("RTSP/1.0 $code $reason\r\nCSeq: $cseq\r\n${headers}Content-Length: ${bytes.size}\r\n\r\n".toByteArray())
                    output.write(bytes); output.flush()
                } finally { writingSince=0 }
            }
        }
        private fun interleaved(ch: Int, bytes: ByteArray) {
            output.write(36); output.write(ch); output.write(bytes.size shr 8); output.write(bytes.size and 255); output.write(bytes)
        }
        private fun writeLoop() {
            try {
                while(alive && playing) {
                    val frame=queue.poll(1,TimeUnit.SECONDS) ?: continue
                    val nals=mutableListOf<ByteArray>()
                    if(frame.key) { sps?.let { nals.add(it) }; pps?.let { nals.add(it) } }
                    nals.addAll(Avc.nals(frame.bytes))
                    val timestamp=(frame.ptsUs*90/1000).toInt()
                    synchronized(outputLock) {
                        writingSince=System.nanoTime()
                        try {
                            nals.forEachIndexed { ni,nal ->
                                val packets=Avc.packets(nal)
                                packets.forEachIndexed { pi,payload ->
                                    val marker=ni==nals.lastIndex && pi==packets.lastIndex
                                    val packet=ByteBuffer.allocate(12+payload.size).put(0x80.toByte()).put((96 or if(marker)128 else 0).toByte())
                                        .putShort((sequence++).toShort()).putInt(timestamp).putInt(ssrc).put(payload).array()
                                    interleaved(channel,packet); packetCount++; octetCount+=payload.size
                                }
                            }
                            val now=System.currentTimeMillis()
                            if(now-lastReport>=1000) {
                                lastReport=now
                                val sr=ByteBuffer.allocate(28).put(0x80.toByte()).put(200.toByte()).putShort(6).putInt(ssrc)
                                    .putInt((now/1000+2208988800L).toInt()).putInt(((now%1000)*4294967296L/1000).toInt())
                                    .putInt(timestamp).putInt(packetCount).putInt(octetCount).array()
                                interleaved(channel+1,sr)
                            }
                            output.flush()
                        } finally { writingSince=0 }
                    }
                }
            } catch(_: Exception) {} finally { close() }
        }
        override fun close() { alive=false; playing=false; runCatching { socket.close() }; queue.clear(); clients.remove(this) }
    }
}
