package com.metashare.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.BitSet;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/** TLS pinning and Metuxa v1 authenticated datagrams. No plaintext fallback. */
public final class SecureTransport {
    private SecureTransport() {}
    public static byte[] unhex(String text, int size) {
        if (text == null || text.length() != size * 2) throw new IllegalArgumentException("Invalid security value");
        byte[] out = new byte[size];
        for (int i=0;i<size;i++) {
            int a=Character.digit(text.charAt(i*2),16), b=Character.digit(text.charAt(i*2+1),16);
            if (a<0 || b<0) throw new IllegalArgumentException("Invalid security value");
            out[i]=(byte)((a<<4)|b);
        }
        return out;
    }
    public static SSLSocket pinnedSocket(String fingerprint) throws GeneralSecurityException, IOException {
        final byte[] expected = unhex(fingerprint,32);
        X509TrustManager trust = new X509TrustManager() {
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            @Override public void checkClientTrusted(X509Certificate[] chain,String type) throws CertificateException { throw new CertificateException("Client certificates unsupported"); }
            @Override public void checkServerTrusted(X509Certificate[] chain,String type) throws CertificateException {
                if (chain == null || chain.length == 0) throw new CertificateException("Missing server certificate");
                try {
                    byte[] actual=MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded());
                    if (!MessageDigest.isEqual(expected,actual)) throw new CertificateException("Server fingerprint mismatch; verify pairing on the server");
                    chain[0].checkValidity();
                } catch (GeneralSecurityException e) { throw new CertificateException("Server identity verification failed",e); }
            }
        };
        SSLContext context=SSLContext.getInstance("TLS");
        context.init(null,new TrustManager[]{trust},null);
        SSLSocket socket=(SSLSocket)context.getSocketFactory().createSocket();
        socket.setEnabledProtocols(new String[]{"TLSv1.3","TLSv1.2"});
        return socket;
    }
    /** Preserves partial lines across socket timeouts, with size/time bounds. */
    public static final class LineReader {
        private final InputStream input;
        private final ByteArrayOutputStream line=new ByteArrayOutputStream();
        private final byte[] buffer=new byte[1024];
        private int pos,limit;
        private long started;
        public LineReader(InputStream input) { this.input=input; }
        public String readLine() throws IOException {
            for (;;) {
                if (started!=0 && System.nanoTime()-started>10_000_000_000L) throw new IOException("Incomplete control message timed out");
                if (pos==limit) {
                    limit=input.read(buffer); pos=0;
                    if (limit<0) { if(line.size()!=0) throw new IOException("Truncated control message"); return null; }
                }
                while (pos<limit) {
                    int b=buffer[pos++] & 255;
                    if (b=='\n') {
                        String result=line.toString(StandardCharsets.US_ASCII.name());
                        line.reset(); started=0; return result;
                    }
                    if (b<32 || b>126 || line.size()>=4096) throw new IOException("Invalid control message");
                    if (started==0) started=System.nanoTime();
                    line.write(b);
                }
            }
        }
    }
    /** One direction, one TLS session. All access serialized for nonce safety. */
    public static final class MediaCipher implements AutoCloseable {
        public static final int SERVER=1, CLIENT=2, OVERHEAD=32, MAX_PLAIN=1500;
        private final byte[] key,prefix;
        private final Cipher cipher;
        private int direction;
        private long next=1,highest;
        private final BitSet seen=new BitSet(2048);
        public MediaCipher(String key,String prefix,int direction) {
            if(direction!=SERVER && direction!=CLIENT) throw new IllegalArgumentException("Invalid direction");
            this.key=unhex(key,32); this.prefix=unhex(prefix,4); this.direction=direction;
            try { cipher=Cipher.getInstance("AES/GCM/NoPadding"); }
            catch(GeneralSecurityException unavailable) { throw new IllegalStateException("AES-GCM unavailable",unavailable); }
        }
        public synchronized byte[] seal(byte[] data) throws GeneralSecurityException {
            if(direction==0 || data.length==0 || data.length>MAX_PLAIN || next>=(1L<<32)) throw new GeneralSecurityException("Media session exhausted or closed");
            byte[] header=new byte[16];
            ByteBuffer.wrap(header).put(new byte[]{'M','X','G','1'}).put((byte)direction).put(new byte[3]).putLong(next++);
            cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce(header,0)));
            cipher.updateAAD(header);
            byte[] ciphertext=cipher.doFinal(data);
            byte[] out=Arrays.copyOf(header,header.length+ciphertext.length);
            System.arraycopy(ciphertext,0,out,16,ciphertext.length); return out;
        }
        public synchronized byte[] open(byte[] data,int offset,int size) {
            if(direction==0 || offset<0 || size<=OVERHEAD || size>MAX_PLAIN+OVERHEAD || offset>data.length-size ||
                data[offset]!='M' || data[offset+1]!='X' || data[offset+2]!='G' || data[offset+3]!='1' ||
                data[offset+4]!=(byte)direction || data[offset+5]!=0 || data[offset+6]!=0 || data[offset+7]!=0) return null;
            long sequence=ByteBuffer.wrap(data,offset+8,8).getLong();
            if(sequence<=0 || sequence>=(1L<<32) || (sequence<=highest && (highest-sequence>=2048 || seen.get((int)(sequence & 2047))))) return null;
            try {
                    cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce(data,offset)));
                cipher.updateAAD(data,offset,16);
                byte[] plain=cipher.doFinal(data,offset+16,size-16);
                if(sequence>highest) {
                    long delta=sequence-highest;
                    if(delta>=2048) seen.clear();
                    else for(long value=highest+1;value<=sequence;value++) seen.clear((int)(value & 2047));
                    highest=sequence;
                }
                seen.set((int)(sequence & 2047)); return plain;
            } catch (GeneralSecurityException e) { return null; }
        }
        private byte[] nonce(byte[] data,int offset) {
            byte[] nonce=Arrays.copyOf(prefix,12);
            System.arraycopy(data,offset+8,nonce,4,8); return nonce;
        }
        @Override public synchronized void close() { Arrays.fill(key,(byte)0); Arrays.fill(prefix,(byte)0); direction=0; seen.clear(); }
    }
}
