package com.metashare.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class CoreTests {
    private static int assertions;
    private static void check(boolean result, String message) { assertions++; if (!result) throw new AssertionError(message); }
    private static void expectInvalid(Runnable task) { assertions++; try { task.run(); throw new AssertionError("Expected invalid input"); } catch (IllegalArgumentException expected) {} }
    private static final class Recorder implements InputState.Sink {
        final List<String> events = new ArrayList<>();
        public void move(float x, float y) { events.add("m:" + x + ":" + y); }
        public void button(int b, boolean down) { events.add("b:" + b + ":" + down); }
        public void scroll(int v, int h) { events.add("s:" + v + ":" + h); }
        public void key(int k, boolean down) { events.add("k:" + k + ":" + down); }
        void clear() { events.clear(); }
        boolean has(String e) { return events.contains(e); }
    }
    public static void main(String[] args) {
        endpoint(); policy(); pointer(); keyboard(); textQueue(); lifecycle(); diagnostics();
        System.out.println("metuxa core: " + assertions + " assertions passed");
    }
    private static void endpoint() {
        check(new HostEndpoint(" PC.local ",7878).key().equals("pc.local:7878"), "hostname normalization");
        check(new HostEndpoint("[fe80::1]",7878).key().equals("[fe80::1]:7878"), "IPv6 brackets");
        check(new HostEndpoint("host",65533).port + 2 == 65535, "upper boundary has monitor room");
        check(new HostEndpoint("HOST",7878).equals(new HostEndpoint("host",7878)), "same host identity");
        check(!new HostEndpoint("pc",7878).equals(new HostEndpoint("pc",7879)), "port part of identity");
        for (String host : new String[] { "", "https://pc", "pc/anything", "pc @host", "a\nb", "-pc", "a..b", "pc?x=1", "pc:7878" }) expectInvalid(() -> new HostEndpoint(host,7878));
        expectInvalid(() -> new HostEndpoint("pc",0)); expectInvalid(() -> new HostEndpoint("pc",65534));
        expectInvalid(() -> HostEndpoint.parse("pc","not-a-port"));
        String pin = "AB".repeat(32); check(HostEndpoint.fingerprint(pin).equals("ab".repeat(32)), "pin normalization");
        check(HostEndpoint.fingerprint("AB:".repeat(31) + "AB").length()==64, "colon pin");
        expectInvalid(() -> HostEndpoint.fingerprint("0".repeat(63))); expectInvalid(() -> HostEndpoint.fingerprint("z".repeat(64)));
        check(HostEndpoint.token("AB".repeat(16)).equals("ab".repeat(16)), "token normalization");
        expectInvalid(() -> HostEndpoint.token("ab".repeat(15))); expectInvalid(() -> HostEndpoint.token("z".repeat(32)));
    }
    private static void policy() {
        Set<String> selected = Set.of("mouse");
        check(PointerPolicy.permits(PointerPolicy.FULL,false,null,selected), "full pointer permits synthetic input");
        check(!PointerPolicy.permits(PointerPolicy.BLOCK,true,"mouse",selected), "block overrides physical device");
        check(!PointerPolicy.permits(PointerPolicy.BLOCK,false,null,selected), "block synthetic input");
        check(PointerPolicy.permits(PointerPolicy.DEVICES,true,"mouse",selected), "selected physical device");
        check(!PointerPolicy.permits(PointerPolicy.DEVICES,false,"mouse",selected), "virtual even with same descriptor denied");
        check(!PointerPolicy.permits(PointerPolicy.DEVICES,true,"other",selected), "unknown device denied");
        check(!PointerPolicy.permits(PointerPolicy.DEVICES,true,null,selected), "missing identity denied");
        check(!PointerPolicy.permits(PointerPolicy.DEVICES,true,"",Set.of("")), "empty identity denied");
        check(!PointerPolicy.permits(999,true,"mouse",selected), "unknown mode fails closed");
        check(!PointerPolicy.permits(PointerPolicy.DEVICES,true,"mouse",Set.of()), "empty allowlist blocks all");
    }
    private static void pointer() {
        Recorder r = new Recorder(); InputState s = new InputState(r);
        s.pointer(0,.2f,.3f,0,0,0); check(r.events.get(0).startsWith("m:"), "move before click"); check(r.has("b:272:true"), "touch press");
        r.clear(); s.pointer(2,.4f,.5f,0,0,0); check(r.events.size()==1, "drag does not duplicate down");
        r.clear(); s.pointer(3,.4f,.5f,0,0,1); check(r.has("b:272:false"), "cancel ignores stale pressed bits");
        r.clear(); s.releasePointer(); check(r.events.isEmpty(), "release idempotent");
        s.pointer(0,.2f,.3f,0,0,3); check(r.has("b:272:true") && r.has("b:273:true"), "simultaneous mouse buttons");
        r.clear(); s.releasePointer(); check(r.has("b:272:false") && r.has("b:273:false"), "toggle releases all mouse buttons");
        r.clear(); s.pointer(0,.2f,.3f,0,0,4); s.pointer(1,.2f,.3f,0,0,4); check(r.has("b:274:false"), "up ignores stale middle bit");
        r.clear(); s.pointer(0,.2f,.3f,0,0,0); s.pointer(10,.2f,.3f,0,0,1); check(r.has("b:272:false"), "hover exit releases");
        r.clear(); s.pointer(7,-9,9,0,0,0); check(r.has("m:0.0:1.0"), "coordinates clamped");
        r.clear(); s.pointer(0,Float.NaN,.5f,0,0,1); check(r.events.isEmpty(), "invalid coordinates never press");
        s.pointer(8,0,0,.4f,0,0); s.pointer(8,0,0,.4f,0,0); check(r.events.isEmpty(), "fractional scroll buffered");
        s.pointer(8,0,0,.4f,1.1f,0); check(r.has("s:-1:-1"), "two-axis scroll directions");
        r.clear(); s.releasePointer(); s.pointer(8,0,0,.9f,0,0); check(r.events.isEmpty(), "release resets scroll remainder");
        s.pointer(8,0,0,Float.NaN,0,0); check(r.events.isEmpty(), "NaN scroll ignored");
        s.pointer(8,0,0,100000,0,0); check(r.has("s:-100:0"), "scroll flood bounded");
        r.clear(); s.pointer(0,.2f,.3f,0,0,1); s.clearDisconnected(); r.clear(); s.releaseAll(); check(r.events.isEmpty(), "disconnect clears local state");
        s.pointer(0,.2f,.3f,0,0,1); check(r.has("b:272:true"), "new session starts fresh button ledger");
    }
    private static void keyboard() {
        Recorder r = new Recorder(); InputState s = new InputState(r);
        s.key(65507,true); s.key(65507,true); check(r.events.size()==1,"repeat down not duplicated");
        s.releasePointer(); check(!r.has("k:65507:false"),"pointer blocking retains keyboard");
        s.key(99,true); r.clear(); s.releaseAll(); check(r.has("k:65507:false") && r.has("k:99:false"),"focus loss releases modifier and printable");
        r.clear(); s.releaseAll(); check(r.events.isEmpty(),"all-release idempotent");
        s.key(65,false); check(r.events.isEmpty(),"unmatched key up ignored");
        s.tap(0xff0d); check(r.has("k:65293:true") && r.has("k:65293:false"),"IME key tap balanced");
        r.clear(); s.key(65,true); s.tap(65); check(r.events.size()==1,"IME cannot release physically held key");
        s.clearDisconnected(); r.clear(); s.key(65,false); check(r.events.isEmpty(),"stale release after reconnect ignored");
        s.key(-1,true); check(r.events.isEmpty(),"invalid keysym denied");
    }
    private static void textQueue() {
        KeyboardQueue q = new KeyboardQueue(); List<String> out = new ArrayList<>();
        KeyboardQueue.Sink sink = new KeyboardQueue.Sink() {
            public void character(int c) { out.add("c:" + c); }
            public void key(int k, boolean d) { out.add("k:" + k + ":" + d); }
        };
        check(q.text("a".repeat(300)), "long paste accepted without wire burst");
        check(q.size()==300,"all paste characters retained");
        q.drain(4,sink); check(out.size()==4 && q.size()==296,"pacing respects fixed budget");
        check(q.key(65293,true) && q.key(65293,false),"Enter queued after paste");
        while(q.size()>0) q.drain(4,sink);
        check(out.get(299).equals("c:97") && out.get(300).equals("k:65293:true"),"Enter cannot overtake pasted text");
        check(q.text("b".repeat(4096)),"capacity accepted");
        check(!q.text("x"),"overflow rejected whole"); check(q.size()==4096,"reject leaves existing queue unchanged");
        q.clear(); check(q.size()==0,"focus/disconnect cancellation clears paste");
        check(!q.text("x".repeat(4097)) && q.size()==0,"oversized paste never silently truncated");
        out.clear(); check(q.text("\ud83d\ude42\ud800a\u0000\n"),"Unicode paste accepted"); q.drain(10,sink);
        check(out.equals(List.of("c:128578","c:97","c:10")),"Unicode codepoints preserved and invalid controls excluded");
    }
    private static void lifecycle() {
        InputLifecycle gate = new InputLifecycle();
        check(!gate.allowsInput(),"new panel cannot forward before resume/focus");
        gate.focused(true); check(!gate.allowsInput(),"focus-before-resume remains blocked");
        gate.resumed(true); check(gate.allowsInput(),"focused resumed panel forwards");
        gate.focused(false); check(!gate.allowsInput(),"late motion/key/IME blocked after focus loss");
        gate.resumed(false); gate.focused(true); check(!gate.allowsInput(),"late focus callback while paused does not rearm");
        gate.resumed(true); check(gate.allowsInput(),"return resumes focused input");
        gate.resumed(false); check(!gate.allowsInput(),"pause first blocks even with stale focus true");
        gate.focused(false); gate.resumed(true); check(!gate.allowsInput(),"resume before focus blocks late events");
        gate.focused(true); check(gate.allowsInput(),"both callbacks restore input");
    }
    private static void diagnostics() {
        InputDiagnostics d = new InputDiagnostics(); d.record(100,1,2,3,4,5);
        check(d.snapshot().contains("observed: 0"),"diagnostics default off"); d.setEnabled(true);
        for (int i=0;i<200;i++) d.record(i*100L,0x1002,1,7,2,0);
        String text=d.snapshot(); check(text.contains("observed: 200; sampled: 200"),"aggregate bounded metadata count");
        check(text.lines().filter(l->l.startsWith("source=")).count()==80,"diagnostic entries bounded");
        check(text.length()<16000,"bounded diagnostic memory");
        d.record(19901,0x1002,1,7,2,0); check(d.snapshot().contains("observed: 201; sampled: 200"),"rate limit counts but does not store flood");
        check(!text.contains("x=")&&!text.contains("y=")&&!text.contains("key="),"no coordinate or key fields");
        d.reset(); check(d.snapshot().contains("observed: 0"),"visible reset semantics");
        check(d.enabled(),"reset retains opt-in"); d.setEnabled(false);
        check(!d.enabled()&&d.snapshot().contains("observed: 0"),"disable erases collected metadata");
    }
}
