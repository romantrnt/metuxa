package com.metashare.client;

public final class MonitorUiTests {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("Monitor UI check " + checks); }
    public static void main(String[] args) {
        for (int angle : new int[] {0, 90, 180, 270}) {
            for (int[] view : new int[][] {{1920,1080},{480,800},{1,1},{800,400},{400,800}}) {
                int[] fit = MonitorGeometry.fit(view[0],view[1],1920,1080,angle);
                check(fit[0] > 0 && fit[1] > 0);
                check((angle % 180 == 0 ? fit[0] : fit[1]) <= view[0]);
                check((angle % 180 == 0 ? fit[1] : fit[0]) <= view[1]);
                check(Math.abs((double)fit[0]/1920 - (double)fit[1]/1080) < .001);
            }
            for (float x : new float[] {0,.23f,.5f,1}) for (float y : new float[] {0,.37f,.5f,1}) {
                float sx = angle == 90 ? 1-y : angle == 180 ? 1-x : angle == 270 ? y : x;
                float sy = angle == 90 ? x : angle == 180 ? 1-y : angle == 270 ? 1-x : y;
                float[] point = MonitorGeometry.inverse(sx,sy,angle);
                check(Math.abs(point[0]-x) < .00001 && Math.abs(point[1]-y) < .00001);
            }
        }
        check(MonitorGeometry.fit(0,100,1920,1080,0)[0] == 0);
        check(MonitorGeometry.rotation(23) == 0);
        check(java.util.Arrays.equals(MonitorGeometry.panelSize(1920, 1080), new int[] {1280, 720}));
        check(java.util.Arrays.equals(MonitorGeometry.panelSize(1080, 1920), new int[] {720, 1280}));
        check(java.util.Arrays.equals(MonitorGeometry.panelSize(1920, 1200), new int[] {1280, 800}));
        check(java.util.Arrays.equals(MonitorGeometry.panelSize(-1, 0), new int[] {1280, 720}));
        DesktopOrientation orientation = new DesktopOrientation();
        check(!orientation.request(true)); orientation.open("physical", true); check(!orientation.request(true));
        orientation.open("virtual", false); check(!orientation.request(true)); orientation.open("virtual", true);
        check(orientation.request(true)); check(!orientation.request(false)); check(orientation.pending()); check(!orientation.awaitingResult());
        check("DISPLAY_ORIENTATION portrait".equals(orientation.claim(100))); check(orientation.claim(101) == null); check(orientation.awaitingResult());
        check(!orientation.expired(12_000_000_099L)); check(orientation.expired(12_000_000_100L)); orientation.complete();
        check(!orientation.pending()); check(orientation.request(false)); orientation.close(); check(orientation.claim(200) == null);
        check(!orientation.request(true));
        DesktopOrientation.Result result = DesktopOrientation.parse("DISPLAY_RESULT ok 1080 1920");
        check(result.width == 1080 && result.height == 1920 && "ok".equals(result.status));
        for (String value : new String[] {"unsupported", "busy", "failed", "restart_required"}) check(value.equals(DesktopOrientation.parse("DISPLAY_RESULT " + value).status));
        for (String invalid : new String[] {"DISPLAY_RESULT", "DISPLAY_RESULT ok 0 1", "DISPLAY_RESULT ok 8193 1920", "DISPLAY_RESULT ok -100 100", "DISPLAY_RESULT ok 100 200 extra", "DISPLAY_RESULT unsupported extra", "DISPLAY_RESULT ok 1e3 1920", "DISPLAY_RESULT untrusted"}) {
            boolean rejected = false; try { DesktopOrientation.parse(invalid); } catch (IllegalArgumentException expected) { rejected = true; } check(rejected);
        }
        PanelVisibility p = new PanelVisibility();
        p.mode(PanelVisibility.AUTO_HIDE); p.reveal(100,4000);
        check(p.visible(100)); check(p.timed(4099)); check(!p.visible(4100));
        p.hovering(true); check(p.visible(9000)); check(!p.timed(100)); p.hovering(false);
        p.interacting(true); check(p.visible(9000)); p.interacting(false);
        p.focused(true); check(p.visible(9000)); p.focused(false); check(!p.visible(9000));
        p.pin(true); check(p.visible(9000)); p.resumed(false); check(p.visible(9000)); check(!p.timed(100)); p.pin(false);
        p.resumed(true); p.mode(PanelVisibility.HOVER); p.reveal(100,4000); check(!p.visible(101));
        p.hovering(true); check(p.visible(9000)); p.hovering(false); check(!p.visible(9000));
        p.pin(true); check(p.visible(9000)); p.pin(false);
        p.mode(PanelVisibility.ALWAYS); check(p.visible(9000)); p.resumed(false); check(p.visible(9000)); check(!p.timed(100));
        int[] cleaned = {0}; SurfaceLifetime lifetime = new SurfaceLifetime(() -> cleaned[0]++);
        check(lifetime.retain()); check(lifetime.retain()); lifetime.retire(); check(cleaned[0] == 0);
        check(!lifetime.retain()); lifetime.release(); check(cleaned[0] == 0); lifetime.release(); check(cleaned[0] == 1);
        lifetime.retire(); check(cleaned[0] == 1);
        SurfaceLifetime unused = new SurfaceLifetime(() -> cleaned[0]++); unused.retire(); check(cleaned[0] == 2); check(!unused.retain());
        SurfaceLifetime active = new SurfaceLifetime(() -> cleaned[0]++); check(active.retain()); active.release(); check(cleaned[0] == 2); active.retire(); check(cleaned[0] == 3);
        System.out.println("Monitor geometry and overlay policy: " + checks + " checks passed");
    }
}
