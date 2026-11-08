package com.metashare.client;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.net.ssl.SSLSocket;

public final class PairingClientTest {
    private static void check(boolean value,String name) { if(!value)throw new AssertionError(name); }
    private static String field(String line,String key) { String start="\""+key+"\":\"";int p=line.indexOf(start);check(p>=0,"event field");p+=start.length();return line.substring(p,line.indexOf('"',p)); }
    static class Attempt implements PairingClient.Listener {
        final CountDownLatch shown=new CountDownLatch(1),done=new CountDownLatch(1);
        final AtomicInteger paired=new AtomicInteger(),failed=new AtomicInteger();
        volatile String code,pin,token;
        public void onCode(String value) { code=value;shown.countDown(); }
        public void onPaired(String p,String t) { pin=p;token=t;paired.incrementAndGet();done.countDown(); }
        public void onFailure(String message) { failed.incrementAndGet();done.countDown(); }
    }
    static class Server implements AutoCloseable {
        final Process process; final BufferedWriter commands; final BlockingQueue<String> events=new LinkedBlockingQueue<>();final int port;
        Server(String binary) throws Exception {
            process=new ProcessBuilder(binary,"--interop").redirectError(ProcessBuilder.Redirect.INHERIT).start();
            commands=new BufferedWriter(new OutputStreamWriter(process.getOutputStream(),StandardCharsets.US_ASCII));
            BufferedReader lines=new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.US_ASCII));
            String first=lines.readLine();check(first!=null&&first.startsWith("PORT "),"interop startup");port=Integer.parseInt(first.substring(5));
            Thread pump=new Thread(()->{try{String line;while((line=lines.readLine())!=null)events.add(line);}catch(IOException e){events.add("IO_ERROR");}});pump.setDaemon(true);pump.start();
        }
        void send(String value) throws IOException { commands.write(value+"\n");commands.flush(); }
        String event(String expected) throws Exception {
            long end=System.nanoTime()+5_000_000_000L;
            while(System.nanoTime()<end) {String next=events.poll(100,TimeUnit.MILLISECONDS);if(next!=null&&next.contains(expected))return next;}
            throw new AssertionError("Missing event "+expected);
        }
        void open() throws Exception { send("OPEN");event("\"state\":\"open\""); }
        public void close() throws Exception {send("QUIT");check(process.waitFor(5,TimeUnit.SECONDS),"interop exits");check(process.exitValue()==0,"interop result");}
    }
    static void vectors() throws Exception {
        String f="000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",c="202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f";
        String s="404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f",id="606162636465666768696a6b6c6d6e6f";
        check(PairingCode.commitment(f,c).equals("557f6cd7437080d8d2bc60df920c9538962852a1d21f73561442a23a6f10914a"),"independent commit vector");
        check(PairingCode.comparison(f,c,s,id).equals("41592 13575"),"independent SAS vector");
        check(!PairingCode.comparison("0".repeat(64),c,s,id).equals("41592 13575"),"different TLS cert rejected by comparison");
    }
    public static void main(String[] args) throws Exception {
        vectors();
        try(Server server=new Server(args[0])) {
            server.open();Attempt a=new Attempt();PairingClient client=new PairingClient("127.0.0.1",server.port,a);
            client.confirm(); // Cannot pre-confirm before there is a comparison.
            client.start();check(a.shown.await(5,TimeUnit.SECONDS),"Java shows comparison");
            String request=server.event("pairing_request");check(field(request,"code").equals(a.code),"C++ and Java displays identical");
            server.send("ACCEPT "+field(request,"id"));server.event("\"reason\":\"approved\"");
            check(!a.done.await(300,TimeUnit.MILLISECONDS),"remote success cannot bypass local confirmation");
            client.confirm();check(a.done.await(5,TimeUnit.SECONDS)&&a.paired.get()==1&&a.failed.get()==0,"both confirmations pair once");
            try(SSLSocket socket=SecureTransport.pinnedSocket(a.pin)) {
                socket.setSoTimeout(3000);socket.connect(new InetSocketAddress("127.0.0.1",server.port));socket.startHandshake();
                socket.getOutputStream().write(("AUTH "+a.token+"\n").getBytes(StandardCharsets.US_ASCII));
                check(new SecureTransport.LineReader(socket.getInputStream()).readLine().equals("HELLO {}"),"returned credentials authenticate pinned reconnect");
            }
            Thread.sleep(2100);server.open();Attempt b=new Attempt();PairingClient cancel=new PairingClient("127.0.0.1",server.port,b);cancel.start();
            check(b.shown.await(5,TimeUnit.SECONDS),"cancel code");request=server.event("pairing_request");
            server.send("ACCEPT "+field(request,"id"));server.event("\"reason\":\"approved\"");
            cancel.cancel();cancel.confirm();Thread.sleep(300);
            check(b.paired.get()==0&&b.failed.get()==0,"cancel suppresses success despite early remote token");
            Thread.sleep(2100);server.open();Attempt c=new Attempt();PairingClient reject=new PairingClient("127.0.0.1",server.port,c);reject.start();
            check(c.shown.await(5,TimeUnit.SECONDS),"reject code");request=server.event("pairing_request");reject.confirm();
            server.send("REJECT "+field(request,"id"));check(c.done.await(5,TimeUnit.SECONDS)&&c.failed.get()==1&&c.paired.get()==0,"desktop reject after local yes");
            Attempt closed=new Attempt();new PairingClient("127.0.0.1",server.port,closed).start();
            check(closed.done.await(5,TimeUnit.SECONDS)&&closed.failed.get()==1&&closed.paired.get()==0,"unsolicited Java pairing rejected");
            Attempt never=new Attempt();PairingClient stopped=new PairingClient("127.0.0.1",server.port,never);stopped.cancel();stopped.start();Thread.sleep(100);
            check(never.shown.getCount()==1&&never.done.getCount()==1,"cancel before start");
        }
        System.out.println("Pairing Java/OpenSSL tests passed (matching codes, both confirmations, early false success, cancel, reject, pinned reconnect).");
    }
}
