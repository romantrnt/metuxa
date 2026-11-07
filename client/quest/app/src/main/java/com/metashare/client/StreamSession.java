package com.metashare.client;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceView;
import org.json.JSONObject;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
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
    }
    private volatile SurfaceView surfaceView;
    private final int monitorIndex;
    private final Listener listener;
    private final Handler main=new Handler(Looper.getMainLooper());
    private volatile boolean running;
    private Thread worker;
    private volatile Socket signalingSocket;
    private volatile VideoDecoder videoDecoder;
    private final SessionInputQueue inputQueue=new SessionInputQueue(512);
    private String host,fingerprint,pairingToken;
    private int basePort;
    private boolean audioEnabled;

    public StreamSession(Context context,SurfaceView surfaceView,int monitorIndex,Listener listener) {
        this.surfaceView=surfaceView; this.monitorIndex=monitorIndex; this.listener=listener;
    }
    public synchronized void configure(String host,int basePort,String fingerprint,String pairingToken,boolean audioEnabled) {
        if(worker!=null) throw new IllegalStateException("Stop the session before changing its server");
        if(host==null || host.trim().isEmpty() || basePort<1 || monitorIndex<0 || basePort+monitorIndex>65535)
            throw new IllegalArgumentException("Invalid server endpoint");
        SecureTransport.unhex(fingerprint,32); SecureTransport.unhex(pairingToken,16);
        this.host=host.trim(); this.basePort=basePort; this.fingerprint=fingerprint;
        this.pairingToken=pairingToken; this.audioEnabled=audioEnabled && monitorIndex==0;
    }
    public void setRenderer(SurfaceView view) {
        surfaceView=view;
        VideoDecoder decoder=videoDecoder;
        if(decoder!=null && view!=null) {
            Surface surface=view.getHolder().getSurface();
            if(surface!=null && surface.isValid()) decoder.setSurface(surface);
        }
    }
    public synchronized void start() {
        if(worker!=null) return;
        if(host==null) { postStatus("Select and pair a server before connecting"); return; }
        running=true;
        worker=new Thread(this::runLoop,"MetuxaStream-"+monitorIndex); worker.start();
    }
    public synchronized void stop() {
        running=false;
        inputQueue.close();
        closeSignalingSocket();
        if(worker!=null) worker.interrupt();
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
    private void enqueueInput(String body) { inputQueue.offer(body); }
    private void runLoop() {
        try {
            int retry=0;
            while(running) {
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
                    if(running) postStatus("Disconnected. Retrying the selected server…");
                } finally { closeSignalingSocket(); }
                if(running) {
                    try { Thread.sleep(Math.min(5000,1000+(retry++)*500L)); }
                    catch(InterruptedException e) { Thread.currentThread().interrupt(); break; }
                }
            }
        } finally {
            running=false;
            synchronized(this) { if(worker==Thread.currentThread()) worker=null; }
        }
    }
    private void negotiateAndRender(InetSocketAddress endpoint) throws Exception {
        VideoDecoder decoder=null;
        AudioDecoder audioDecoder=null;
        RtpReceiver receiver=null;
        Thread inputSender=null;
        SecureTransport.MediaCipher decryptor=null,encryptor=null;
        final AtomicBoolean sessionActive=new AtomicBoolean(true);
        try {
            SSLSocket socket=SecureTransport.pinnedSocket(fingerprint);
            signalingSocket=socket;
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
            SurfaceView view=surfaceView;
            Surface surface=view==null?null:view.getHolder().getSurface();
            if(surface==null || !surface.isValid()) throw new IOException("Render surface unavailable");
            decoder.init(surface,codec,width,height,new VideoDecoder.Listener() {
                @Override public void onFirstFrameRendered() { postStatus(""); }
                @Override public void onFrameResolutionChanged(int w,int h) { main.post(()->listener.onStreamSize(w,h)); }
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
                catch(IOException disconnected) { try { socket.close(); } catch(IOException ignored) {} }
            },"MetuxaInput-"+monitorIndex);
            inputSender.start();
            socket.setSoTimeout(3000);
            long lastHeartbeat=System.nanoTime();
            while(running) {
                try {
                    String line=sigIn.readLine();
                    if(line==null || "BYE".equals(line)) break;
                    if("INPUT_STATE true".equals(line) || "INPUT_STATE false".equals(line)) {
                        final boolean available=line.endsWith("true");
                        boolean changed=inputQueue.setEnabled(inputSession,available);
                        if(changed) main.post(()->listener.onInputCapability(available));
                    } else if(!"PING".equals(line)) throw new IOException("Unexpected control message");
                    lastHeartbeat=System.nanoTime();
                } catch(SocketTimeoutException idle) {
                    if(System.nanoTime()-lastHeartbeat>10_000_000_000L) throw new IOException("Server heartbeat timed out");
                    synchronized(writeLock) { sendLine(sigOut,"PING"); }
                }
            }
        } finally {
            sessionActive.set(false);
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
            if(audioDecoder!=null) try { audioDecoder.release(); } catch(Exception ignored) {}
            main.post(()->{ listener.onInputCapability(false); listener.onDisconnected(); });
        }
    }
    private static void sendLine(OutputStream output,String line) throws IOException {
        if(line.length()>4096 || line.indexOf('\n')>=0 || line.indexOf('\r')>=0) throw new IOException("Invalid outgoing control message");
        output.write((line+"\n").getBytes(StandardCharsets.US_ASCII)); output.flush();
    }
    private void postStatus(String text) { main.post(()->listener.onStatus(text)); }
    private void closeSignalingSocket() {
        Socket socket=signalingSocket; signalingSocket=null;
        if(socket!=null) try { socket.close(); } catch(IOException ignored) {}
    }
}
