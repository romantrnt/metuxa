package com.metashare.client;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.net.ssl.SSLSocket;

/** One user-requested PAIR1 attempt. No discovery trust, persistence or retries.
 * Listener callbacks run on the worker thread; UI callers must check attempt
 * identity again when delivering a callback on their main thread. */
public final class PairingClient {
    public interface Listener {
        void onCode(String code);
        void onPaired(String fingerprint,String token);
        void onFailure(String message);
    }
    private final String host;
    private final int port;
    private final Listener listener;
    private volatile boolean cancelled;
    private boolean started, codeShown, confirmed;
    private volatile SSLSocket socket;
    public PairingClient(String host,int port,Listener listener) {
        if(host==null || host.trim().isEmpty() || port<1 || port>65535 || listener==null)
            throw new IllegalArgumentException("Invalid pairing endpoint");
        this.host=host.trim(); this.port=port; this.listener=listener;
    }
    public synchronized void start() {
        if(started || cancelled) return;
        started=true; new Thread(this::run,"MetuxaPairing").start();
    }
    /** User compared all displayed digits with the PC. Never call automatically. */
    public synchronized void confirm() {
        if(!cancelled && codeShown) { confirmed=true; notifyAll(); }
    }
    public synchronized void cancel() {
        cancelled=true; notifyAll();
        SSLSocket active=socket;
        if(active!=null) try { active.close(); } catch(IOException ignored) {}
    }
    private String read(SecureTransport.LineReader reader,long deadline) throws IOException {
        while(!cancelled && System.nanoTime()<deadline) {
            try {
                String value=reader.readLine();
                if(value==null) throw new IOException("Pairing connection closed");
                return value;
            } catch(SocketTimeoutException timeout) { /* Absolute deadline stays fixed. */ }
        }
        throw new IOException("Pairing cancelled or expired");
    }
    private static void send(OutputStream stream,String line) throws IOException {
        stream.write((line+"\n").getBytes(StandardCharsets.US_ASCII)); stream.flush();
    }
    private void run() {
        byte[] random=new byte[32];
        boolean displayed=false;
        try {
            long deadline=System.nanoTime()+60_000_000_000L;
            SSLSocket connection=SecureTransport.pairingSocket();
            synchronized(this) { if(cancelled) { connection.close(); return; } socket=connection; }
            connection.setTcpNoDelay(true); connection.setSoTimeout(5000);
            connection.connect(new InetSocketAddress(host,port),3000); connection.startHandshake();
            if(cancelled) return;
            // Derive the binding from this completed TLS handshake, never from
            // discovery, a response field, a saved profile, or a separate socket.
            String fingerprint=PairingCode.hex(MessageDigest.getInstance("SHA-256").digest(
                connection.getSession().getPeerCertificates()[0].getEncoded()));
            new SecureRandom().nextBytes(random);
            String nonce=PairingCode.hex(random);
            SecureTransport.LineReader reader=new SecureTransport.LineReader(connection.getInputStream());
            OutputStream output=connection.getOutputStream(); connection.setSoTimeout(1000);
            send(output,"PAIR1 "+PairingCode.commitment(fingerprint,nonce));
            String[] challenge=read(reader,deadline).split(" ",-1);
            if(challenge.length!=3 || !"PAIR_CHALLENGE1".equals(challenge[0])) throw new IOException("Pairing unavailable");
            SecureTransport.unhex(challenge[1],16); SecureTransport.unhex(challenge[2],32);
            String code=PairingCode.comparison(fingerprint,nonce,challenge[2],challenge[1]);
            send(output,"PAIR_REVEAL1 "+nonce);
            if(!"PAIR_WAIT1".equals(read(reader,deadline))) throw new IOException("Pairing refused");
            synchronized(this) { if(cancelled) return; displayed=true; codeShown=true; listener.onCode(code); }
            String result=read(reader,deadline);
            if(result.length()!=40 || !result.startsWith("PAIRED1 ")) throw new IOException("Pairing refused");
            String token=result.substring(8); SecureTransport.unhex(token,16);
            synchronized(this) {
                // A fake PC can claim remote approval. It cannot supply the
                // user's independent comparison with the real PC display.
                while(!cancelled && !confirmed && System.nanoTime()<deadline) wait(100);
                if(!cancelled && confirmed && System.nanoTime()<deadline) listener.onPaired(fingerprint,token);
                else if(!cancelled) throw new IOException("Local comparison expired");
            }
        } catch(Exception failure) {
            // Remote text and exception details can contain private transcript
            // data. Show only local fixed text and never log the transcript.
            synchronized(this) {
                if(!cancelled) listener.onFailure(displayed?
                    "Pairing ended. Open pairing on the PC and try again.":
                    "Could not pair. On the PC, choose Allow pairing, then try again.");
            }
        } finally {
            Arrays.fill(random,(byte)0);
            SSLSocket active=socket; socket=null;
            if(active!=null) try { active.close(); } catch(IOException ignored) {}
        }
    }
}
