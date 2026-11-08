package com.metashare.client;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import org.json.JSONObject;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocket;

/** One explicitly selected, pinned Metuxa host. Never auto-trusts discovery. */
public final class StreamSession {
    private static final String TAG="StreamSession";
    public interface Listener {
        void onStatus(String text);
        void onStreamSize(int width,int height);
        void onAvailableMonitors(int count);
        default void onConnected() {}
        default void onDisconnected() {}
        default void onInputCapability(boolean available) {}
        default void onDisplayCapability(String source, boolean supported) {}
        default void onDisplayResult(String result, int width, int height) {}
    }
    private final RemoteSurfaceView surfaceView;
    private final int monitorIndex;
    private final Listener listener;
    private final AppSettings settings;
    private final Handler main=new Handler(Looper.getMainLooper());
    private volatile boolean running;
    private Thread worker;
    private volatile SocketShutdown signalingSocket;
    private volatile VideoDecoder videoDecoder;
    private final SessionInputQueue inputQueue=new SessionInputQueue(512);
    private String host,fingerprint,pairingToken;
    private int basePort;
    private boolean audioEnabled;
    private final DesktopOrientation orientation = new DesktopOrientation();
    private boolean explicitReconnect;

    public StreamSession(Context context,RemoteSurfaceView surfaceView,int monitorIndex,Listener listener) {
        this.surfaceView=surfaceView; this.monitorIndex=monitorIndex; this.listener=listener;
        this.settings=new AppSettings(context.getApplicationContext());
    }
    public synchronized void configure(String host,int basePort,String fingerprint,String pairingToken,boolean audioEnabled) {
        if(worker!=null) throw new IllegalStateException("Stop the session before changing its server");
        if(host==null || host.trim().isEmpty() || basePort<1 || monitorIndex<0 || monitorIndex>=16 || basePort+monitorIndex>65535)
            throw new IllegalArgumentException("Invalid server endpoint");
        SecureTransport.unhex(fingerprint,32); SecureTransport.unhex(pairingToken,16);
        this.host=host.trim(); this.basePort=basePort; this.fingerprint=fingerprint;
        this.pairingToken=pairingToken; this.audioEnabled=audioEnabled && monitorIndex==0;
    }
    public synchronized void start() {
        if(worker!=null) return;
        if(host==null) { postStatus("Select and pair a server before connecting"); return; }
        running=true;
        worker=new Thread(this::runLoop,"MetuxaStream-"+monitorIndex); worker.start();
    }
    public synchronized void stop() {
        running=false;
        orientation.close();
        inputQueue.close();
        SocketShutdown closing=signalingSocket;
        if(closing!=null) closing.request(); // TLS close can write; never perform it on the UI thread.
        if(worker!=null) worker.interrupt();
    }
    public synchronized boolean requestOrientation(boolean portrait) {
        if (!running || !orientation.request(portrait)) return false;
        inputQueue.releaseAll(); return true;
    }
    public void sendPointerMove(float x,float y) {
        if(!Float.isFinite(x) || !Float.isFinite(y)) return;
        enqueueInput(String.format(Locale.US,"m %.4f %.4f",Math.max(0,Math.min(1,x)),Math.max(0,Math.min(1,y))));
    }
    public void sendPointerButton(int button,boolean pressed) { enqueueInput("b "+button+(pressed?" 1":" 0")); }
    public void sendScroll(int vertical,int horizontal) { enqueueInput("s "+vertical+" "+horizontal); }
    public void sendKey(int keysym,boolean pressed) { enqueueInput("k "+keysym+(pressed?" 1":" 0")); }
    /** Invalidates old unclaimed input; release follows any already-claimed write. */
    public void releaseAllInput() { inputQueue.releaseAll(); }
    private synchronized void enqueueInput(String body) { if (!orientation.pending()) inputQueue.offer(body); }
    private void runLoop() {
        boolean ownsMonitor=false;
        try {
            // Recreated Activities have new StreamSession objects. Keep the old
            // session's decoder/audio teardown ahead of its replacement without
            // joining a worker on the UI thread or reusing a timed-out codec.
            MonitorSessionGate.acquire(monitorIndex);
            ownsMonitor=true;
            int retry=0;
            while(running) {
                explicitReconnect=false;
                try {
                    postStatus("Connecting securely to "+host+"…");
                    InetAddress address=InetAddress.getByName(host);
                    if(!running) break;
                    negotiateAndRender(new InetSocketAddress(address,basePort+monitorIndex));
                    retry=0;
                } catch(SSLHandshakeException securityFailure) {
                    if(running) postStatus("Server identity could not be verified. Check the fingerprint on the server and pair again.");
                    running=false;
                } catch(Exception failure) {
                    // Never log wire content, credentials, session keys, or the
                    // HELLO payload. Error messages from peers are untrusted.
                    Log.w(TAG,"Secure session ended ("+failure.getClass().getSimpleName()+")");
                    if(running && settings.reconnect()) postStatus("Disconnected. Retrying the selected server…");
                } finally { closeSignalingSocket(); }
                if(running && !settings.reconnect() && !explicitReconnect) {
                    postStatus("Connection closed. Choose Connect to try again."); running=false;
                }
                if(running && !explicitReconnect) {
                    try { Thread.sleep(Math.min(5000,1000+(retry++)*500L)); }
                    catch(InterruptedException e) { Thread.currentThread().interrupt(); break; }
                }
            }
        } catch(InterruptedException stopped) {
            Thread.currentThread().interrupt();
        } finally {
            running=false;
            if(ownsMonitor) MonitorSessionGate.release(monitorIndex);
            synchronized(this) { if(worker==Thread.currentThread()) worker=null; }
        }
    }
    private void negotiateAndRender(InetSocketAddress endpoint) throws Exception {
        VideoDecoder decoder=null;
        RenderSurface.Lease renderLease=null;
        AudioDecoder audioDecoder=null;
        RtpReceiver receiver=null;
        Thread inputSender=null;
        SecureTransport.MediaCipher decryptor=null,encryptor=null;
        final AtomicBoolean sessionActive=new AtomicBoolean(true);
        try {
            SSLSocket socket=SecureTransport.pinnedSocket(fingerprint);
            final SocketShutdown socketShutdown=new SocketShutdown(socket,"MetuxaSocketClose-"+monitorIndex);
            signalingSocket=socketShutdown;
            if(!running) return;
            socket.setTcpNoDelay(true); socket.setSoTimeout(5000);
            socket.connect(endpoint,3000); socket.startHandshake();
            SecureTransport.LineReader sigIn=new SecureTransport.LineReader(socket.getInputStream());
            OutputStream sigOut=socket.getOutputStream();
            sendLine(sigOut,"AUTH "+pairingToken);
            String helloLine=sigIn.readLine();
            if(helloLine==null || !helloLine.startsWith("HELLO ")) {
                postStatus("Pairing was refused. Check the current code on the server.");
                running=false; return;
            }
            JSONObject hello=new JSONObject(helloLine.substring(6));
            if(hello.getInt("version")!=5) throw new IOException("Unsupported secure protocol");
            final int monitorCount=hello.getInt("monitor_count");
            if(monitorCount<1 || monitorCount>16) throw new IOException("Invalid monitor count");
            main.post(()->listener.onAvailableMonitors(monitorCount));
            JSONObject security=hello.getJSONObject("security");
            if(security.getInt("version")!=1 || !"AES-256-GCM".equals(security.getString("cipher"))) throw new IOException("Unsupported media security");
            decryptor=new SecureTransport.MediaCipher(security.getString("s2c_key"),security.getString("s2c_prefix"),SecureTransport.MediaCipher.SERVER);
            encryptor=new SecureTransport.MediaCipher(security.getString("c2s_key"),security.getString("c2s_prefix"),SecureTransport.MediaCipher.CLIENT);
            int mediaPort=hello.getInt("udp_port");
            if(mediaPort<1 || mediaPort>65535) throw new IOException("Invalid media endpoint");
            boolean canInput=hello.optBoolean("input",false);
            JSONObject video=hello.getJSONObject("video");
            String codec=video.getString("codec");
            int width=video.getInt("width"),height=video.getInt("height"),videoSsrc=video.getInt("ssrc"),videoPt=video.getInt("pt");
            int videoClock=video.getInt("clock");
            if((!"h264".equals(codec) && !"h265".equals(codec)) || width<16 || width>8192 || height<16 || height>8192 || videoPt<96 || videoPt>127 || videoClock!=90000)
                throw new IOException("Invalid video format");
            JSONObject display=hello.optJSONObject("display");
            final String source=display==null?"unknown":display.optString("source","unknown");
            final boolean canOrient=display!=null && "virtual".equals(source) && display.optBoolean("orientation",false);
            main.post(()->{ listener.onStreamSize(width,height); listener.onDisplayCapability(source,canOrient); });
            int audioSsrc=-1,audioPt=-1,audioClock=48000;
            if(audioEnabled && hello.has("audio")) {
                JSONObject audio=hello.getJSONObject("audio");
                int rate=audio.getInt("rate"),channels=audio.getInt("channels");
                audioSsrc=audio.getInt("ssrc"); audioPt=audio.getInt("pt"); audioClock=audio.getInt("clock");
                if(!"opus".equals(audio.getString("codec")) || rate!=48000 || audioClock!=48000 || channels<1 || channels>2 || audioPt<96 || audioPt>127)
                    throw new IOException("Invalid audio format");
                audioDecoder=new AudioDecoder();
                try { audioDecoder.init(rate,channels); }
                catch(Exception unsupportedAudio) { audioDecoder.release(); audioDecoder=null; }
            }
            decoder=new VideoDecoder(); videoDecoder=decoder;
            renderLease=surfaceView.acquireSurface();
            Surface surface=renderLease==null?null:renderLease.surface();
            if(surface==null || !surface.isValid()) throw new IOException("Render surface unavailable");
            decoder.init(surface,codec,width,height,new VideoDecoder.Listener() {
                @Override public void onFirstFrameRendered() { postStatus(""); }
                @Override public void onFrameResolutionChanged(int w,int h) { main.post(()->{ if(sessionActive.get()) listener.onStreamSize(w,h); }); }
            });
            final VideoDecoder activeVideo=decoder;
            final AudioDecoder activeAudio=audioDecoder;
            receiver=new RtpReceiver(activeVideo::feed,activeAudio==null?null:activeAudio::feed);
            receiver.configure(videoSsrc,videoPt,codec,videoClock,audioSsrc,audioPt,audioClock);
            receiver.configureSecurity(endpoint.getAddress(),mediaPort,decryptor,encryptor);
            decoder.setKeyframeRequester(receiver::requestKeyframe);
            receiver.start(0);
            sendLine(sigOut,"READY {\"port\":"+receiver.getLocalPort()+"}");
            if(!"START".equals(sigIn.readLine())) throw new IOException("Expected START");
            final SessionInputQueue.Session inputSession;
            synchronized(this) {
                if(!running) return;
                inputSession=inputQueue.open(canInput);
                orientation.open(source,canOrient);
            }
            main.post(()->{ listener.onInputCapability(canInput); listener.onConnected(); });
            receiver.requestKeyframe();
            final Object writeLock=new Object();
            inputSender=new Thread(()->{
                try {
                    while(sessionActive.get() && running) {
                        SessionInputQueue.Pending pending=inputQueue.take(inputSession);
                        if(pending==null) return;
                        synchronized(writeLock) {
                            if(!sessionActive.get() || !running) return;
                            // Claim only after waiting for any heartbeat write.
                            // UI policy changes never wait for this socket lock.
                            String line=inputQueue.claim(inputSession,pending);
                            if(line!=null) sendLine(sigOut,line);
                        }
                    }
                } catch(InterruptedException ignored) { Thread.currentThread().interrupt(); }
                catch(IOException disconnected) { socketShutdown.request(); }
            },"MetuxaInput-"+monitorIndex);
            inputSender.start();
            socket.setSoTimeout(500);
            long lastHeartbeat=System.nanoTime(),lastPing=lastHeartbeat;
            while(running) {
                long now=System.nanoTime();
                String command=orientation.claim(now);
                if(command!=null) synchronized(writeLock) {
                    explicitReconnect=true; // A lost reply still requires learning the resulting desktop size.
                    // Release before changing desktop geometry, even in view-only mode.
                    sendLine(sigOut,"INPUT r"); sendLine(sigOut,command);
                }
                if(orientation.expired(now)) {
                    explicitReconnect=true;
                    main.post(()->listener.onDisplayResult("timeout",0,0));
                    break; // Reconnect to learn the actual state; never replay a command.
                }
                try {
                    String line=sigIn.readLine();
                    if(line==null || "BYE".equals(line)) break;
                    if("INPUT_STATE true".equals(line) || "INPUT_STATE false".equals(line)) {
                        final boolean available=line.endsWith("true");
                        boolean changed=inputQueue.setEnabled(inputSession,available);
                        if(changed) main.post(()->listener.onInputCapability(available));
                    } else if(line.startsWith("DISPLAY_RESULT ")) {
                        if(!orientation.awaitingResult()) throw new IOException("Unsolicited display result");
                        if(handleDisplayResult(DesktopOrientation.parse(line))) break;
                    } else if(!"PING".equals(line)) throw new IOException("Unexpected control message");
                    lastHeartbeat=System.nanoTime();
                } catch(SocketTimeoutException idle) {
                    if(System.nanoTime()-lastHeartbeat>10_000_000_000L) throw new IOException("Server heartbeat timed out");
                    if(System.nanoTime()-lastPing>=3_000_000_000L) {
                        synchronized(writeLock) { sendLine(sigOut,"PING"); }
                        lastPing=System.nanoTime();
                    }
                }
            }
        } finally {
            sessionActive.set(false);
            orientation.close();
            inputQueue.close();
            closeSignalingSocket(); // unblocks writes before joining the sender
            if(inputSender!=null) {
                inputSender.interrupt();
                RtpReceiver.joinForCleanup(inputSender,1000);
            }
            if(receiver!=null) receiver.stop();
            if(decryptor!=null) decryptor.close();
            if(encryptor!=null) encryptor.close();
            videoDecoder=null;
            if(decoder!=null) try { decoder.release(); } catch(Exception ignored) {}
            if(renderLease!=null) renderLease.close();
            if(audioDecoder!=null) try { audioDecoder.release(); } catch(Exception ignored) {}
            main.post(()->{ listener.onInputCapability(false); listener.onDisconnected(); });
        }
    }
    private boolean handleDisplayResult(DesktopOrientation.Result result) {
        orientation.complete();
        main.post(()->listener.onDisplayResult(result.status,result.width,result.height));
        if("restart_required".equals(result.status)) {
            inputQueue.close(); explicitReconnect=false; running=false; return true;
        }
        if("ok".equals(result.status) || "failed".equals(result.status)) {
            inputQueue.close(); explicitReconnect=true; return true;
        }
        explicitReconnect=false; return false;
    }
    private static void sendLine(OutputStream output,String line) throws IOException {
        if(line.length()>4096 || line.indexOf('\n')>=0 || line.indexOf('\r')>=0) throw new IOException("Invalid outgoing control message");
        output.write((line+"\n").getBytes(StandardCharsets.US_ASCII)); output.flush();
    }
    private void postStatus(String text) { main.post(()->listener.onStatus(text)); }
    private void closeSignalingSocket() {
        // Only called by the background session worker. Even if stop() already
        // requested close, await its completion before this monitor is reused.
        SocketShutdown closing=signalingSocket; signalingSocket=null;
        if(closing!=null) closing.await();
    }
}
