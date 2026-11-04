package com.genymobile.scrcpy;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Looper;
import android.system.Os;
import com.genymobile.scrcpy.device.Device;
import org.json.JSONObject;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;

/** Shell-privileged, ADB-independent supervisor. Only this process owns its encoder child. */
public final class DirectAgent {
    private final JSONObject config;
    private final SSLSocketFactory tls;
    private final String jar = System.getProperty("java.class.path").split(File.pathSeparator)[0];
    private final ScheduledExecutorService awake = Executors.newSingleThreadScheduledExecutor();
    private final AwakePolicy awakePolicy = new AwakePolicy();
    private volatile boolean stopping;
    private volatile String generation;
    private String host;
    private Mirror mirror;
    private volatile boolean linkConnected;
    private volatile boolean companionAllowed=false;
    private volatile SSLSocket managementSocket;
    private WorkerLocalControl localControl;
    private File preferenceFile;
    private volatile long managementNetwork;
    private volatile long networkRevision;
    private volatile String networkError="";
    private void networkChanged() {
        networkRevision++;
        SSLSocket active=managementSocket;
        if(active!=null)try { active.close(); } catch(IOException ignored) { }
    }

    private synchronized JSONObject localCommand(JSONObject request) throws Exception {
        String action=request.getString("action");
        if (action.equals("set_allowed")) {
            Object value=request.get("allowed");
            if (!(value instanceof Boolean)) throw new IOException("Invalid permission");
            File temporary=new File(preferenceFile.getPath()+".new");
            Files.write(temporary.toPath(),new JSONObject().put("allowed",value).toString().getBytes(StandardCharsets.UTF_8));
            Os.chmod(temporary.getPath(),0600);
            Files.move(temporary.toPath(),preferenceFile.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            companionAllowed=(Boolean)value;
            if (!companionAllowed) { awakePolicy.standbyOverride(false);stopMirror(null); }
        } else if (action.equals("screen_off")) {
            if(mirror==null || !mirror.streaming || mirror.closed)throw new IOException("Start screen sharing first");
            execute(new String[] {"/system/bin/cmd","statusbar","collapse"});
            mirror.screenOff();
        } else if (action.equals("stop_worker")) {
            // The local transport acknowledges before stopping the worker.
        } else if (!action.equals("status")) throw new IOException("Unsupported companion action");
        return new JSONObject().put("ok",true).put("version",1).put("worker_version",BuildConfig.VERSION_NAME)
            .put("allowed",companionAllowed).put("connected",linkConnected)
            .put("network_route",managementNetwork!=0?"companion_wifi":"default")
            .put("network_handle",managementNetwork).put("network_error",networkError)
            .put("streaming",mirror!=null && mirror.streaming && !mirror.closed)
            .put("screen_off_supported",true).put("screen_guard",mirror==null?0:mirror.guardState)
            .put("awake_requested",awakePolicy.requested()).put("stopping",stopping || action.equals("stop_worker"));
    }

    DirectAgent(JSONObject config) throws Exception {
        this.config = config;
        final String pin = config.getString("certificate_sha256");
        if (!pin.matches("[a-f0-9]{64}") || !config.getString("token").matches("[a-f0-9]{64}")) {
            throw new IOException("Invalid provisioning");
        }
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null,new TrustManager[] {new PinnedTrust(pin)},new SecureRandom());
        tls = context.getSocketFactory();
    }

    static JSONObject read(InputStream input) throws Exception {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        for (int i=0;i<=4096;i++) {
            int value = input.read();
            if (value < 0) throw new EOFException();
            if (value == '\n') return new JSONObject(line.toString("UTF-8"));
            line.write(value);
        }
        throw new IOException("Oversized command");
    }

    static void write(OutputStream out, JSONObject value) throws IOException {
        out.write((value.toString()+"\n").getBytes(StandardCharsets.UTF_8));out.flush();
    }

    private SSLSocket connect(String role, String session) throws Exception {
        Socket raw = new Socket();
        SSLSocket socket = null;
        try {
            long network=0;
            try {
                if(localControl!=null)network=localControl.bind(raw,host,config.getInt("port"));
            } catch(Exception routeFailure) {
                networkError="bind_"+routeFailure.getClass().getSimpleName();
                raw.close();raw=new Socket();
            }
            try {
                raw.connect(new InetSocketAddress(host,config.getInt("port")),4000);
                if(network!=0)networkError="";
            } catch(Exception routeFailure) {
                if(network==0)throw routeFailure;
                // Never weaken TLS; retry only the raw connection on the default network.
                networkError="connect_"+routeFailure.getClass().getSimpleName();
                raw.close();raw=new Socket();network=0;
                raw.connect(new InetSocketAddress(host,config.getInt("port")),4000);
            }
            socket = (SSLSocket) tls.createSocket(raw,host,config.getInt("port"),true);
            socket.setEnabledProtocols(new String[] {"TLSv1.2"});
            socket.setTcpNoDelay(true);socket.setSoTimeout(6000);socket.startHandshake();
            JSONObject hello = new JSONObject().put("version",1).put("token",config.getString("token")).put("role",role).put("worker_version",BuildConfig.VERSION_NAME).put("policy_version",1);
            if (session != null) hello.put("generation",generation).put("session",session);
            write(socket.getOutputStream(),hello);
            JSONObject reply = read(socket.getInputStream());
            if (!reply.optBoolean("ok")) throw new IOException("Receiver rejected connection");
            if (session == null) { generation = reply.getString("generation");managementNetwork=network; }
            return socket;
        } catch (Exception e) { if (socket != null) socket.close(); else raw.close();throw e; }
    }

    private synchronized void stopMirror(String id) {
        if (mirror != null && (id == null || id.equals(mirror.id))) {
            mirror.close();mirror = null;awakePolicy.streaming(false);
        }
    }

    private synchronized JSONObject command(JSONObject request) throws Exception {
        String action = request.getString("action");
        JSONObject reply = new JSONObject().put("ok",true);
        switch (action) {
            case "ping":
                boolean wasAwake=awakePolicy.requested();
                awakePolicy.standbyOverride(companionAllowed && request.optBoolean("keep_awake",false));
                if (!request.optBoolean("enabled",true)) stopMirror(null);
                if (!wasAwake && awakePolicy.requested()) wake();
                return reply.put("awake_requested",awakePolicy.requested()).put("streaming",mirror!=null && mirror.streaming && !mirror.closed)
                    .put("mirror_allowed",companionAllowed).put("network_route",managementNetwork!=0?"companion_wifi":"default")
                    .put("network_error",networkError)
                    .put("panel_off",mirror==null || mirror.panelOff==null?JSONObject.NULL:mirror.panelOff)
                    .put("screen_guard",mirror==null?0:mirror.guardState);
            case "start":
                if (!companionAllowed) throw new IOException("Paused on phone");
                String id = request.getString("session");
                if (!id.matches("[a-f0-9]{32}")) throw new IOException("Invalid session");
                stopMirror(null);
                wake();
                mirror = new Mirror(id);mirror.start();return reply;
            case "stop": stopMirror(request.getString("session"));return reply;
            case "shutdown": stopping = true;return reply;
            default:
                return reply.put("output",execute(DirectActions.arguments(action)));
        }
    }

    private static String execute(String[] args) throws Exception {
        Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread drain = new Thread(() -> {
            byte[] buffer = new byte[1024];
            try (InputStream stream = process.getInputStream()) {
                int n;
                while ((n=stream.read(buffer))>=0) {
                    synchronized (output) { if (output.size()<1024) output.write(buffer,0,Math.min(n,1024-output.size())); }
                }
            } catch (IOException ignored) { }
        },"phone-action-output");
        drain.setDaemon(true);drain.start();
        if (!process.waitFor(5,TimeUnit.SECONDS)) {
            process.destroyForcibly();drain.join(1000);throw new IOException("Phone action timed out");
        }
        drain.join(1000);
        if (process.exitValue()!=0) throw new IOException("Phone action failed");
        synchronized (output) { return output.toString("UTF-8"); }
    }

    private void wake() throws Exception {
        execute(new String[] {"/system/bin/input","keyevent","KEYCODE_WAKEUP"});
    }

    private void run() throws Exception {
        awake.scheduleAtFixedRate(() -> {
            if (awakePolicy.requested()) Device.keepActive(0);
        },0,4,TimeUnit.SECONDS);
        int delay = 1;
        boolean reported = false;
        String lastNetworkError="";
        while (!stopping) {
            long routeRevision=networkRevision;
            try (SSLSocket management = connect("management",null)) {
                managementSocket=management;
                if(routeRevision!=networkRevision)throw new IOException("Network changed during connection");
                linkConnected=true;
                delay=1;reported=false;management.setSoTimeout(12000);awakePolicy.connected(true);
                while (!stopping) {
                    JSONObject request = read(management.getInputStream());
                    JSONObject response;
                    try { response = command(request); }
                    catch (Exception e) { response = new JSONObject().put("ok",false); }
                    write(management.getOutputStream(),response);
                }
            } catch (Exception e) {
                // No identifiers, network addresses, action output or credentials in logs.
                if (!reported) System.err.println("Direct phone link unavailable: "+e.getClass().getSimpleName());
                if(!lastNetworkError.equals(networkError)) {
                    System.err.println("Companion route unavailable: "+networkError);lastNetworkError=networkError;
                }
                reported=true;
            } finally { managementSocket=null;linkConnected=false;awakePolicy.connected(false);stopMirror(null); }
            if (!stopping) { Thread.sleep(delay*1000L);delay=Math.min(5,delay*2); }
        }
        awake.shutdownNow();
        if (localControl!=null) localControl.close();
    }

    private final class Mirror extends Thread implements Closeable {
        final String id;
        final List<Closeable> channels = new ArrayList<>();
        final List<Thread> pumps = new ArrayList<>();
        volatile boolean closed;
        volatile boolean streaming;
        Process child;
        volatile int guardState;
        volatile Boolean panelOff;
        private ControlRelay relay;
        private final Object guardAck=new Object();
        void screenOff() throws Exception {
            ControlRelay target=relay;if(target==null || closed)throw new IOException("No active phone controls");
            synchronized(guardAck) {
                guardState=0;target.send(new byte[] {(byte)128});
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
                while(guardState==0 && !closed && System.nanoTime()<deadline)guardAck.wait(100);
                if(guardState!=1)throw new IOException("Screen-off mode could not be armed");
            }
        }
        Mirror(String id) { super("phone-direct-mirror");this.id=id;setDaemon(true); }

        synchronized <T extends Closeable> T own(T channel) throws IOException {
            if (closed) { channel.close();throw new IOException("Session cancelled"); }
            channels.add(channel);return channel;
        }

        LocalSocket local(String name) throws Exception {
            long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
            while (!closed && System.nanoTime()<deadline) {
                LocalSocket socket = new LocalSocket();
                try { socket.connect(new LocalSocketAddress(name));return own(socket); }
                catch (IOException e) { socket.close();Thread.sleep(50); }
            }
            throw new IOException("Encoder socket unavailable");
        }

        void pump(InputStream in, OutputStream out) {
            Thread worker = new Thread(() -> {
                byte[] buffer = new byte[32768];
                try {
                    int n;
                    while (!closed && (n=in.read(buffer))>=0) { out.write(buffer,0,n); }
                } catch (IOException ignored) { }
                finally { close(); }
            },"phone-direct-channel");
            worker.setDaemon(true);pumps.add(worker);worker.start();
        }

        @Override public void run() {
            try {
                int scid = new SecureRandom().nextInt() & 0x7fffffff;
                ProcessBuilder builder = new ProcessBuilder("/system/bin/app_process","-Dkigeyes.direct=true","/","com.genymobile.scrcpy.Server",
                    BuildConfig.VERSION_NAME,"scid="+String.format(Locale.ROOT,"%08x",scid),
                    "tunnel_forward=true","audio=false","control=true","send_device_meta=false","clipboard_autosync=false",
                    "power_on=false","keep_active=false","cleanup=false","max_size=960","max_fps=30","video_bit_rate=4000000",
                    "video_codec_options=i-frame-interval=1","log_level=warn");
                builder.environment().put("CLASSPATH",jar);
                builder.redirectOutput(new File("/dev/null")).redirectError(new File("/dev/null")).redirectInput(new File("/dev/null"));
                synchronized (this) { if (closed) return;child=builder.start(); }
                String name = "scrcpy_"+String.format(Locale.ROOT,"%08x",scid);
                LocalSocket video = local(name);
                if (video.getInputStream().read()!=0) throw new IOException("Encoder handshake failed");
                LocalSocket control = local(name);
                SSLSocket networkVideo = own(connect("video",id));networkVideo.setSoTimeout(0);
                SSLSocket networkControl = own(connect("control",id));networkControl.setSoTimeout(0);
                pump(video.getInputStream(),networkVideo.getOutputStream());
                relay=new ControlRelay(control.getOutputStream());
                Thread controls=new Thread(() -> {
                    try { relay.forward(networkControl.getInputStream()); } catch(IOException ignored) { } finally { close(); }
                },"phone-control-relay");controls.setDaemon(true);pumps.add(controls);controls.start();
                Thread replies=new Thread(() -> {
                    try { ControlRelay.replies(control.getInputStream(),networkControl.getOutputStream(),state -> {
                        if(state==4 || state==5) panelOff=state==5;
                        else synchronized(guardAck) { guardState=state;guardAck.notifyAll(); }
                    }); } catch(IOException ignored) { } finally { close(); }
                },"phone-control-replies");replies.setDaemon(true);pumps.add(replies);replies.start();
                synchronized (this) { if (!closed) { streaming=true;awakePolicy.streaming(true); } }
                child.waitFor();
            } catch (Exception e) { System.err.println("Direct encoder session ended: "+e.getClass().getSimpleName()); }
            finally {
                close();
                for (Thread worker:pumps) { try { worker.join(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
            }
        }

        @Override public synchronized void close() {
            if (closed) return;
            closed = true;awakePolicy.streaming(false);
            for (Closeable channel:channels) { try { channel.close(); } catch (IOException ignored) { } }
            if (child != null) {
                // Socket EOF lets Controller.stop restore any panel lease before termination.
                try { if (!child.waitFor(2,TimeUnit.SECONDS)) child.destroy(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt();child.destroy(); }
            }
        }
    }

    public static void main(String[] args) {
        int result = 1;
        try {
            if (args.length!=1 || Os.getuid()!=2000) throw new IOException("Start through authorized ADB shell");
            File configFile = new File(args[0]);
            try (RandomAccessFile lockFile = new RandomAccessFile(new File(configFile.getParentFile(),"agent.lock"),"rw");
                 FileLock lock = lockFile.getChannel().tryLock()) {
                if (lock == null) throw new IOException("Worker already running");
                Files.write(new File(configFile.getParentFile(),"agent.pid").toPath(),
                    Integer.toString(Os.getpid()).getBytes(StandardCharsets.US_ASCII));
                Looper.prepareMainLooper();Workarounds.apply();
                DirectAgent agent = new DirectAgent(new JSONObject(new String(Files.readAllBytes(configFile.toPath()),StandardCharsets.UTF_8)));
                agent.host = agent.config.getString("host");
                agent.preferenceFile=new File(configFile.getParentFile(),"companion.json");
                if(agent.preferenceFile.exists()) {
                    try { agent.companionAllowed=new JSONObject(new String(Files.readAllBytes(agent.preferenceFile.toPath()),StandardCharsets.UTF_8)).getBoolean("allowed"); }
                    catch(Exception e) { agent.companionAllowed=false; }
                }
                agent.localControl=new WorkerLocalControl(agent.config.optInt("app_uid",-1),agent::localCommand,() -> {
                    agent.stopping=true;agent.awakePolicy.connected(false);agent.stopMirror(null);
                    SSLSocket active=agent.managementSocket;if(active!=null)try { active.close(); } catch(IOException ignored) { }
                },agent::networkChanged);
                Runtime.getRuntime().addShutdownHook(new Thread(() -> { agent.stopping=true;agent.stopMirror(null);agent.awake.shutdownNow();if(agent.localControl!=null)agent.localControl.close(); }));
                agent.run();result=0;
            }
        } catch (Exception e) { System.err.println("Direct phone worker stopped: "+e.getClass().getSimpleName()); }
        System.exit(result);
    }
}
