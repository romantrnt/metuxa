package com.metashare.client;
import java.io.*;
import java.net.*;
import java.util.*;
import javax.net.ssl.*;
public final class SecureTransportTest {
    static void check(boolean value,String name) { if(!value) throw new AssertionError(name); }
    static String hex(byte[] value) { StringBuilder s=new StringBuilder(); for(byte b:value) s.append(String.format("%02x",b)); return s.toString(); }
    public static void main(String[] args) throws Exception {
        String key="1".repeat(64), prefix="01020304";
        byte[] plain=SecureTransport.unhex("80600001000000010000000765aa",14);
        SecureTransport.MediaCipher sender=new SecureTransport.MediaCipher(key,prefix,1), receiver=new SecureTransport.MediaCipher(key,prefix,1);
        byte[] packet=sender.seal(plain);
        check(hex(packet).equals(args[0]),"C++/Java exact wire vector");
        check(Arrays.equals(plain,receiver.open(packet,0,packet.length)),"valid media");
        check(receiver.open(packet,0,packet.length)==null,"replay");
        SecureTransport.MediaCipher wrong=new SecureTransport.MediaCipher("2".repeat(64),prefix,1);
        check(wrong.open(packet,0,packet.length)==null,"wrong key");
        wrong=new SecureTransport.MediaCipher(key,prefix,2);
        check(wrong.open(packet,0,packet.length)==null,"reflection");
        check(receiver.open(plain,0,plain.length)==null,"plaintext");
        packet=sender.seal(plain); byte[] corrupt=packet.clone(); corrupt[corrupt.length-1]^=1;
        check(receiver.open(corrupt,0,corrupt.length)==null,"modified tag");
        check(Arrays.equals(plain,receiver.open(packet,0,packet.length)),"forgery leaves replay window intact");
        List<byte[]> packets=new ArrayList<>(); for(int i=0;i<2050;i++) packets.add(sender.seal(plain));
        byte[] latest=packets.get(2049), old=packets.get(0), late=packets.get(2048);
        check(receiver.open(latest,0,latest.length)!=null,"forward jump");
        check(receiver.open(old,0,old.length)==null,"old window");
        check(receiver.open(late,0,late.length)!=null,"reordered packet");
        check(receiver.open(late,0,late.length)==null,"reordered replay");
        receiver.close(); check(receiver.open(latest,0,latest.length)==null,"closed keys");
        SecureTransport.LineReader reader=new SecureTransport.LineReader(new ByteArrayInputStream("HELLO {}\nSTART\n".getBytes()));
        check("HELLO {}".equals(reader.readLine()) && "START".equals(reader.readLine()) && reader.readLine()==null,"coalesced lines");
        try { new SecureTransport.LineReader(new ByteArrayInputStream(("x".repeat(4097)+"\n").getBytes())).readLine(); throw new AssertionError("oversize accepted"); } catch(IOException expected) {}
        try { new SecureTransport.LineReader(new ByteArrayInputStream("truncated".getBytes())).readLine(); throw new AssertionError("truncated accepted"); } catch(IOException expected) {}
        int port=Integer.parseInt(args[1]); String pin=args[2],token=args[3];
        try(SSLSocket socket=SecureTransport.pinnedSocket("0".repeat(64))) {
            socket.connect(new InetSocketAddress("127.0.0.1",port)); socket.setSoTimeout(3000);
            try { socket.startHandshake(); throw new AssertionError("wrong pin accepted"); } catch(SSLHandshakeException expected) {}
        }
        try(SSLSocket socket=SecureTransport.pinnedSocket(pin)) {
            socket.connect(new InetSocketAddress("127.0.0.1",port)); socket.setSoTimeout(3000); socket.startHandshake();
            OutputStream out=socket.getOutputStream();
            for(byte b:("AUTH "+token+"\n").getBytes()) { out.write(b); out.flush(); }
            reader=new SecureTransport.LineReader(socket.getInputStream());
            String hello=reader.readLine();
            check(hello!=null && hello.startsWith("HELLO ") && hello.contains("AES-256-GCM"),"pinned TLS to actual RtpServer");
            out.write("READY {\"port\":12345}\n".getBytes()); out.flush();
            check("START".equals(reader.readLine()),"authenticated control across languages");
            check("INPUT_STATE false".equals(reader.readLine()),"initial view-only state");
            boolean enabled=false,disabledAfterEnable=false;
            long start=System.nanoTime(),lastHeartbeat=start;
            socket.setSoTimeout(2000);
            while(System.nanoTime()-start<14_000_000_000L) {
                try {
                    String message=reader.readLine();
                    check(message!=null,"session remained connected beyond 12 seconds");
                    if("INPUT_STATE true".equals(message)) enabled=true;
                    else if("INPUT_STATE false".equals(message)) { if(enabled)disabledAfterEnable=true; }
                    else check("PING".equals(message),"known heartbeat reply");
                    lastHeartbeat=System.nanoTime();
                } catch(SocketTimeoutException idle) {
                    check(System.nanoTime()-lastHeartbeat<10_000_000_000L,"bidirectional heartbeat timeout");
                    out.write("PING\n".getBytes()); out.flush();
                }
            }
            check(enabled && disabledAfterEnable,"live input disable/enable capability updates");
            out.write("BYE\n".getBytes()); out.flush();
        }
        System.out.println("Java and cross-language TLS/media tests passed");
    }
}
