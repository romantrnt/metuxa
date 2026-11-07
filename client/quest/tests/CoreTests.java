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
        endpoint(); policy(); granularActions(); pointer(); multiSourceOwnership(); ownershipWithoutClicks(); auxiliaryButtonOwnership(); nativeFocusKeyRelease(); keyboard(); textQueue(); lifecycle(); diagnostics();
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
        check(!PointerPolicy.permits(PointerPolicy.EXCEPT_DEVICES,true,"mouse",selected), "denylist blocks selected physical device");
        check(PointerPolicy.permits(PointerPolicy.EXCEPT_DEVICES,true,"other",selected), "denylist allows other identified physical device");
        check(PointerPolicy.permits(PointerPolicy.EXCEPT_DEVICES,true,"mouse",Set.of()), "empty denylist allows identified physical pointer");
        check(!PointerPolicy.permits(PointerPolicy.EXCEPT_DEVICES,false,"other",selected), "denylist rejects virtual device even if unlisted");
        check(!PointerPolicy.permits(PointerPolicy.EXCEPT_DEVICES,true,null,selected), "denylist rejects unknown descriptor");
        check(!PointerPolicy.permits(PointerPolicy.EXCEPT_DEVICES,true,"",selected), "denylist rejects empty descriptor");
        check(!PointerPolicy.permits(PointerPolicy.EXCEPT_DEVICES,false,null,Set.of()), "empty denylist still rejects unknown virtual");
    }
    private static void granularActions() {
        for (int mask = 0; mask < 16; mask++) {
            boolean move = (mask & 1) != 0, click = (mask & 2) != 0, scroll = (mask & 4) != 0, keyboard = (mask & 8) != 0;
            InputOptions options = new InputOptions(PointerPolicy.FULL, move, click, scroll, keyboard);
            Recorder r = new Recorder(); InputState state = new InputState(r); state.configure(options);
            state.pointer(0,.8f,.7f,0,0,0); state.pointer(2,.6f,.5f,0,0,1);
            state.pointer(7,.4f,.3f,0,0,0); state.pointer(11,.2f,.1f,0,0,2);
            state.pointer(8,.2f,.1f,2,1,2); state.pointer(12,.2f,.1f,0,0,0); state.pointer(1,.2f,.1f,0,0,1);
            state.key(42,true); state.key(42,false); state.tap(43);
            check(r.events.stream().anyMatch(e -> e.startsWith("m:")) == move, "movement independent mask " + mask);
            check(r.events.stream().anyMatch(e -> e.startsWith("b:")) == click, "click/drag independent mask " + mask);
            check(r.events.stream().anyMatch(e -> e.startsWith("s:")) == scroll, "scroll independent mask " + mask);
            check(r.events.stream().anyMatch(e -> e.startsWith("k:")) == keyboard, "hardware/IME key independent mask " + mask);
            if (!move) check(r.events.stream().noneMatch(e -> e.startsWith("m:")), "click/scroll never leak coordinates mask " + mask);
            int expectedPreset = mask == 15 ? InputOptions.FULL_REMOTE : (mask & 7) == 0 ? keyboard ? InputOptions.KEYBOARD_ONLY : InputOptions.VIEW_ONLY : InputOptions.CUSTOM;
            check(options.preset() == expectedPreset, "preset derived from actual action settings " + mask);
            r.clear(); state.configure(InputOptions.preset(InputOptions.VIEW_ONLY)); r.clear();
            state.pointer(0,.5f,.5f,4,4,7); state.key(42,true); state.tap(43);
            check(r.events.isEmpty(), "view-only rejects every action after custom policy " + mask);
        }
        Recorder r = new Recorder(); InputState state = new InputState(r);
        state.pointer(0,.5f,.5f,0,0,7); state.key(65507,true); r.clear();
        state.configure(new InputOptions(PointerPolicy.FULL,true,false,true,false));
        check(r.has("b:272:false") && r.has("b:273:false") && r.has("b:274:false"), "click disable releases every held button");
        check(r.has("k:65507:false"), "keyboard disable releases held modifier");
        r.clear(); state.pointer(2,.8f,.8f,0,0,7); state.key(65507,true); state.tap(99);
        check(r.events.size()==1 && r.events.get(0).startsWith("m:"), "old pressed bits and keys blocked after disabling clicks/keyboard");
        state.configure(new InputOptions(PointerPolicy.FULL,false,true,false,true)); r.clear();
        state.pointer(0,.9f,.1f,0,0,0); state.pointer(2,.1f,.9f,0,0,0); state.pointer(1,.1f,.9f,0,0,0);
        check(r.events.equals(List.of("b:272:true","b:272:false")), "click-only operates at host cursor with zero coordinate messages");
        state.configure(InputOptions.full()); r.clear(); state.pointer(8,0,0,.8f,.8f,0);
        state.configure(new InputOptions(PointerPolicy.FULL,true,true,false,true)); state.configure(InputOptions.full()); r.clear();
        state.pointer(8,0,0,.3f,.3f,0); check(r.events.isEmpty(), "scroll off clears both fractional accumulators before reenable");
        state.configure(new InputOptions(PointerPolicy.BLOCK,true,true,true,true)); r.clear();
        state.pointer(0,.5f,.5f,3,3,7); state.key(65,true); state.key(65,false);
        check(r.events.equals(List.of("k:65:true","k:65:false")), "source-level pointer block does not block keyboard");
        for (int mode : new int[] {PointerPolicy.DEVICES, PointerPolicy.EXCEPT_DEVICES}) {
            InputOptions filtered = new InputOptions(mode,true,true,true,true);
            check(filtered.preset()==InputOptions.CUSTOM,"filtered devices never mislabeled full remote");
        }
        check(InputOptions.preset(InputOptions.FULL_REMOTE).preset()==InputOptions.FULL_REMOTE,"full preset roundtrip");
        check(InputOptions.preset(InputOptions.KEYBOARD_ONLY).preset()==InputOptions.KEYBOARD_ONLY,"keyboard preset roundtrip");
        check(InputOptions.preset(InputOptions.VIEW_ONLY).preset()==InputOptions.VIEW_ONLY,"view preset roundtrip");
        expectInvalid(() -> InputOptions.preset(InputOptions.CUSTOM));
        java.util.Map<String,Boolean> persisted = new java.util.HashMap<>();
        InputOptions original = InputOptions.read(PointerPolicy.FULL,(key,fallback)->persisted.getOrDefault(key,fallback));
        check(original.move && original.click && original.scroll && original.keyboard,"old installs preserve full action defaults");
        persisted.put(InputOptions.MOVE,false); persisted.put(InputOptions.CLICK,true); persisted.put(InputOptions.SCROLL,false); persisted.put(InputOptions.KEYBOARD,false);
        InputOptions newWindow = InputOptions.read(PointerPolicy.EXCEPT_DEVICES,(key,fallback)->persisted.getOrDefault(key,fallback));
        check(!newWindow.move && newWindow.click && !newWindow.scroll && !newWindow.keyboard && newWindow.pointerMode==PointerPolicy.EXCEPT_DEVICES,"new windows load persisted granular policy");
        check(original.move && original.scroll && original.keyboard,"policy snapshots remain immutable");
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
    private static final class PointerHarness {
        final Recorder recorder = new Recorder();
        final InputState state = new InputState(recorder);
        final PointerOwnership ownership = new PointerOwnership();
        void configure(InputOptions options) { ownership.reset(); state.configure(options); }
        int send(int device, String descriptor, boolean allowed, int action, int buttons) {
            int decision = ownership.gate(device, descriptor, allowed, action, buttons);
            if (decision == PointerOwnership.IGNORE) return decision;
            if (decision == PointerOwnership.RELEASE_OWNER || action == 3 || action == 10) {
                ownership.reset(); state.releasePointer(); return decision;
            }
            ownership.forwarded(device, descriptor, action, buttons);
            state.pointer(action, device / 100f, .5f, action == 8 ? 1 : 0, 0, buttons);
            return decision;
        }
        void reset() { configure(InputOptions.full()); }
        void remove(int device) { if (ownership.removed(device)) state.releasePointer(); }
    }
    private static void multiSourceOwnership() {
        PointerHarness h = new PointerHarness();
        h.send(1,"mouse",true,7,0); h.send(3,"other-mouse",true,7,0); h.recorder.clear();
        h.send(1,"mouse",true,0,1);
        check(h.ownership.ownsDevice(1),"mouse drag acquires device ownership");
        check(h.recorder.has("b:272:true"),"owner mouse press forwarded"); h.recorder.clear();
        for (int action : new int[] {7,3,10,8,0,1}) {
            check(h.send(2,"excluded-hand",false,action,action==0?1:0)==PointerOwnership.IGNORE,"denied non-owner action ignored " + action);
            check(h.recorder.events.isEmpty(),"denied source never releases/moves/clicks/scrolls owner " + action);
        }
        for (int action : new int[] {7,3,10,8,0,1}) {
            check(h.send(3,"other-mouse",true,action,action==0?1:0)==PointerOwnership.IGNORE,"allowed non-owner cannot steal drag " + action);
            check(h.recorder.events.isEmpty(),"other allowed source leaves active drag untouched " + action);
        }
        h.send(1,"mouse",true,2,1);
        check(h.recorder.events.equals(List.of("m:0.01:0.5")),"owner move after excluded hover does not release/repress");
        h.recorder.clear(); h.send(1,"mouse",true,1,0);
        check(h.recorder.has("b:272:false") && !h.ownership.ownsDevice(1),"owner up releases drag and ownership");
        h.recorder.clear();
        check(h.send(3,"other-mouse",true,2,1)==PointerOwnership.IGNORE,"ignored press cannot return as pressed MOVE tail");
        check(h.send(3,"other-mouse",true,8,1)==PointerOwnership.IGNORE,"ignored press cannot return as pressed SCROLL tail");
        check(h.recorder.events.isEmpty(),"pressed tails do not inject any event");
        h.send(3,"other-mouse",true,1,0); h.recorder.clear(); h.send(3,"other-mouse",true,0,1);
        check(h.recorder.has("b:272:true"),"other mouse can click after release and fresh down");
        h.send(3,"other-mouse",true,1,0); h.reset(); h.recorder.clear();
        h.send(4,"touch",true,0,0); h.recorder.clear();
        h.send(1,"mouse",true,7,0); h.send(2,"excluded-hand",false,3,0);
        check(h.recorder.events.isEmpty() && h.ownership.ownsDevice(4),"zero-button touch contact retains exclusive ownership");
        h.send(4,"touch",true,2,0);
        check(!h.recorder.has("b:272:false"),"touch MOVE does not lose synthetic held primary");
        h.recorder.clear(); h.send(4,"touch",true,3,0);
        check(h.recorder.has("b:272:false") && !h.ownership.ownsDevice(4),"owner CANCEL releases touch contact");
        h.reset(); h.send(1,"mouse",true,7,0); h.send(1,"mouse",true,0,1); h.recorder.clear();
        h.remove(2); check(h.recorder.events.isEmpty() && h.ownership.ownsDevice(1),"unrelated unplug cannot release owner");
        h.remove(1); check(h.recorder.has("b:272:false") && !h.ownership.ownsDevice(1),"owner unplug releases drag");
        h.recorder.clear(); h.send(1,"mouse",true,2,1); check(h.recorder.events.isEmpty(),"old unplugged pressed tail cannot rearm");
        h.send(1,"mouse",true,7,0); h.send(1,"mouse",true,0,1); h.recorder.clear();
        check(h.send(1,"mouse",false,7,1)==PointerOwnership.RELEASE_OWNER,"owner becoming denied releases itself");
        check(h.recorder.has("b:272:false"),"denied owner has no stuck button");
        h.send(1,"mouse",true,7,0); h.send(1,"mouse",true,0,1); h.recorder.clear();
        check(h.send(1,"reused-device-id",true,7,0)==PointerOwnership.RELEASE_OWNER,"descriptor change cannot inherit active owner");
        check(h.recorder.has("b:272:false"),"identity change releases old held button");
        h.reset(); h.send(1,"mouse",true,7,0); h.send(1,"mouse",true,0,1); h.reset(); h.recorder.clear();
        h.send(2,"other",true,7,0); h.recorder.clear();
        check(h.send(1,"mouse",true,11,3)==PointerOwnership.IGNORE,"fresh right with stale left after reset cannot re-press left");
        check(h.send(1,"mouse",true,12,1)==PointerOwnership.IGNORE,"partial release is not a neutral sample");
        check(h.recorder.events.isEmpty(),"another device neutral cannot rearm held source");
        h.send(1,"mouse",true,12,0); h.recorder.clear(); h.send(1,"mouse",true,11,2);
        check(h.recorder.has("b:273:true") && !h.recorder.has("b:272:true"),"fully neutral owner then right press sends only fresh right");
        h.reset(); check(!h.ownership.ownsDevice(1),"policy/focus reset clears ownership");
        PointerOwnership bounded = new PointerOwnership();
        for(int device=0;device<40;device++) bounded.gate(device,"d"+device,true,7,0);
        check(bounded.gate(0,"d0",true,0,1)==PointerOwnership.IGNORE,"bounded neutral history eviction fails closed");
    }
    private static void ownershipWithoutClicks() {
        InputOptions moveAndScroll = new InputOptions(PointerPolicy.FULL, true, false, true, false);
        for (int press : new int[] {0, 11}) {
            PointerHarness h = new PointerHarness(); h.configure(moveAndScroll);
            h.send(1,"mouse",true,7,0); h.send(1,"mouse",true,press,1); h.recorder.clear();
            check(h.send(1,"mouse",true,2,1)==PointerOwnership.FORWARD,"movement continues with clicks disabled after press " + press);
            check(h.recorder.has("m:0.01:0.5"),"click-disabled gesture forwards allowed coordinates " + press);
            h.recorder.clear(); h.send(1,"mouse",true,8,1);
            check(h.recorder.has("s:-1:0"),"scroll continues with clicks disabled after press " + press);
            check(h.recorder.events.stream().noneMatch(e -> e.startsWith("b:")),"click-disabled gesture never presses a remote button " + press);
            h.recorder.clear(); h.send(2,"denied",false,10,0); h.send(3,"other",true,3,0);
            check(h.recorder.events.isEmpty() && h.ownership.ownsDevice(1),"other sources cannot interrupt click-disabled contact " + press);
            h.configure(moveAndScroll); h.recorder.clear(); h.send(2,"other",true,7,0); h.recorder.clear();
            check(h.send(1,"mouse",true,2,1)==PointerOwnership.IGNORE && h.recorder.events.isEmpty(),"click-disabled reset still rejects stale pressed tails " + press);
            h.send(1,"mouse",true,12,0); h.send(1,"mouse",true,press,1); h.recorder.clear(); h.send(1,"mouse",true,2,1);
            check(h.recorder.has("m:0.01:0.5"),"same-device neutral and new press rearm click-disabled movement " + press);
        }
    }
    private static void auxiliaryButtonOwnership() {
        PointerHarness h = new PointerHarness();
        h.configure(new InputOptions(PointerPolicy.FULL, true, false, true, false));
        h.send(1,"mouse",true,7,0); h.send(1,"mouse",true,11,8); h.recorder.clear();
        check(h.send(1,"mouse",true,2,8)==PointerOwnership.FORWARD,"unmapped physical button still owns movement gesture");
        h.send(1,"mouse",true,8,8);
        check(h.recorder.has("m:0.01:0.5") && h.recorder.has("s:-1:0"),"unmapped button does not suppress allowed movement or scroll");
        check(h.recorder.events.stream().noneMatch(e -> e.startsWith("b:")),"unmapped button does not inject a remote click");
        h.send(1,"mouse",true,12,0); check(!h.ownership.ownsDevice(1),"auxiliary button release ends ownership");
    }
    private static void nativeFocusKeyRelease() {
        HardwareKeyLedger ledger = new HardwareKeyLedger(); Recorder recorder = new Recorder(); InputState state = new InputState(recorder);
        check(ledger.press(113,65507),"remote Ctrl-down recorded"); state.key(65507,true); recorder.clear();
        // Same production operation runs before deciding whether a native View gets this event.
        boolean nativeButtonFocused = true, localEventDelivered = false;
        Integer released = ledger.release(113); if(released!=null) state.key(released,false);
        if(nativeButtonFocused) localEventDelivered = true;
        check(recorder.has("k:65507:false"),"Ctrl-up after local-button focus releases remote modifier");
        check(localEventDelivered,"native control still receives its intended key-up event");
        check(!ledger.contains(113) && ledger.release(113)==null,"held release is routed exactly once");
        recorder.clear(); Integer unheld=ledger.release(114); if(unheld!=null) state.key(unheld,false);
        check(recorder.events.isEmpty(),"native-only key-up does not create remote key input");
        ledger.press(113,65507); state.key(65507,true); ledger.clear(); state.releaseAll(); recorder.clear();
        check(ledger.release(113)==null && recorder.events.isEmpty(),"policy/focus reset ignores stale later key-up");
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
