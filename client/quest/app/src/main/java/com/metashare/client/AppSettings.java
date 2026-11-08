package com.metashare.client;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class AppSettings {
    static final int MAX_PROFILES = 8;
    final SharedPreferences preferences;
    AppSettings(Context context) { preferences = context.getApplicationContext().getSharedPreferences("metuxa.settings.v1", Context.MODE_PRIVATE); }
    int pointerMode() { return preferences.getInt("pointer_mode", PointerPolicy.FULL); }
    void pointerMode(int mode) { preferences.edit().putInt("pointer_mode", mode).apply(); }
    Set<String> devices() { return new HashSet<>(preferences.getStringSet("devices", new HashSet<>())); }
    void devices(Set<String> devices) { preferences.edit().putStringSet("devices", new HashSet<>(devices)).apply(); }
    Set<String> excludedDevices() { return new HashSet<>(preferences.getStringSet("excluded_devices", new HashSet<>())); }
    void excludedDevices(Set<String> devices) { preferences.edit().putStringSet("excluded_devices", new HashSet<>(devices)).apply(); }
    InputOptions inputOptions() { return InputOptions.read(pointerMode(), preferences::getBoolean); }
    void inputOptions(InputOptions options) {
        preferences.edit().putInt("pointer_mode", options.pointerMode)
            .putBoolean(InputOptions.MOVE, options.move).putBoolean(InputOptions.CLICK, options.click)
            .putBoolean(InputOptions.SCROLL, options.scroll).putBoolean(InputOptions.KEYBOARD, options.keyboard).apply();
    }
    int theme() { return bounded("theme", 0, 0, 2); } // 0 dark, 1 light, 2 system
    int textScale() { return bounded("text_scale", 100, 100, 150); }
    int topBar() { return bounded("top_bar", PanelVisibility.AUTO_HIDE, 0, 2); }
    int bottomBar() { return bounded("bottom_bar", PanelVisibility.AUTO_HIDE, 0, 2); }
    int hideDelay() { return bounded("hide_delay", 4, 2, 15); }
    int panelOpacity() { return bounded("panel_opacity", 95, 70, 100); }
    boolean keepAwake() { return preferences.getBoolean("keep_awake", true); }
    boolean discoverOnOpen() { return preferences.getBoolean("discover_on_open", true); }
    boolean confirmDisconnect() { return preferences.getBoolean("confirm_disconnect", true); }
    boolean autoCheckUpdates() { return preferences.getBoolean("auto_check_updates", false); }
    boolean autoDownloadUpdates() { return preferences.getBoolean("auto_download_updates", false); }
    int rotation(String host, int monitor) { return MonitorGeometry.rotation(preferences.getInt("rotation:" + host + ":" + monitor, 0)); }
    void rotation(String host, int monitor, int value) { integer("rotation:" + host + ":" + monitor, MonitorGeometry.rotation(value)); }
    void integer(String key, int value) { preferences.edit().putInt(key, value).apply(); }
    void flag(String key, boolean value) { preferences.edit().putBoolean(key, value).apply(); }
    private int bounded(String key, int fallback, int minimum, int maximum) { return Math.max(minimum, Math.min(maximum, preferences.getInt(key, fallback))); }
    boolean audio() { return preferences.getBoolean("audio", true); }
    int monitors() { return Math.max(1, Math.min(3, preferences.getInt("monitors", 1))); }
    boolean reconnect() { return preferences.getBoolean("reconnect", true); }
    String autoHost() { return preferences.getString("auto_host", ""); }
    String lastHost() { return preferences.getString("last_host", ""); }
    void options(boolean audio, int monitors, boolean auto, String host) {
        preferences.edit().putBoolean("audio", audio).putInt("monitors", Math.max(1, Math.min(3, monitors)))
                .putString("auto_host", auto ? host : "").apply();
    }
    List<Profile> profiles() {
        List<Profile> result = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(preferences.getString("profiles", "[]"));
            for (int i = 0; i < Math.min(MAX_PROFILES, array.length()); i++) {
                JSONObject o = array.getJSONObject(i);
                result.add(new Profile(new HostEndpoint(o.getString("host"), o.getInt("port")), o.optString("pin"), o.optString("secret")));
            }
        } catch (Exception ignored) { /* malformed settings never trigger a connection */ }
        return result;
    }
    Profile find(HostEndpoint endpoint) { for (Profile p : profiles()) if (p.endpoint.equals(endpoint)) return p; return null; }
    void save(HostEndpoint endpoint, String pin, String token) throws Exception {
        List<Profile> all = profiles(); all.removeIf(p -> p.endpoint.equals(endpoint));
        all.add(0, new Profile(endpoint, pin, CredentialVault.seal(token, endpoint.key())));
        write(all); preferences.edit().putString("last_host", endpoint.key()).apply();
    }
    void forget(HostEndpoint endpoint) throws Exception {
        List<Profile> all = profiles(); all.removeIf(p -> p.endpoint.equals(endpoint)); write(all);
        SharedPreferences.Editor edit = preferences.edit();
        if (autoHost().equals(endpoint.key())) edit.remove("auto_host");
        if (lastHost().equals(endpoint.key())) edit.remove("last_host"); edit.apply();
    }
    private void write(List<Profile> profiles) throws Exception {
        JSONArray array = new JSONArray();
        for (Profile p : profiles.subList(0, Math.min(MAX_PROFILES, profiles.size()))) array.put(new JSONObject()
                .put("host", p.endpoint.host).put("port", p.endpoint.port).put("pin", p.pin).put("secret", p.sealedToken));
        preferences.edit().putString("profiles", array.toString()).apply();
    }
    static final class Profile {
        final HostEndpoint endpoint; final String pin, sealedToken;
        Profile(HostEndpoint endpoint, String pin, String token) { this.endpoint = endpoint; this.pin = pin; this.sealedToken = token; }
        String token() throws Exception { return CredentialVault.open(sealedToken, endpoint.key()); }
    }
}
