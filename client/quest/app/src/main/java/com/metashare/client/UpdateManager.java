package com.metashare.client;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.HttpsURLConnection;

/** Explicit GitHub release updates. The Android installer remains the authority to install. */
public final class UpdateManager implements AutoCloseable {
    public interface Listener {
        void onStatus(String status);
        void onRelease(Release release);
        void onError(String error);
    }
    public static final class Release {
        public final String version;
        public final int versionCode;
        private final byte[] envelope;
        private Release(UpdateManifest manifest, byte[] signed) {
            version=manifest.version; versionCode=manifest.versionCode; envelope=signed.clone();
        }
    }
    private final Context context;
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final UpdateOperationState operation=new UpdateOperationState();
    private volatile HttpsURLConnection connection;
    private static final int PROTOCOL=5;
    private static final Object FLOOR_LOCK=new Object(), INSTALL_LOCK=new Object();
    static final String PREFS="metuxa-update-install";
    public UpdateManager(Context context) { this.context=context.getApplicationContext(); }
    public boolean needsInstallPermission() { return !context.getPackageManager().canRequestPackageInstalls(); }
    public void requestInstallPermission(Activity activity) {
        try { activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:"+context.getPackageName()))); }
        catch(android.content.ActivityNotFoundException | SecurityException e) {
            android.widget.Toast.makeText(activity,"Open Android settings and allow metuxa to install unknown apps, or install the signed APK manually.",android.widget.Toast.LENGTH_LONG).show();
        }
    }
    public static String lastInstallStatus(Context c) { return c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString("status",""); }
    private interface Work { void run(int token) throws Exception; }
    private synchronized void execute(Listener listener,Work work) {
        final int token=operation.begin();
        if(token==-1) return;
        if(token==-2) { listener.onError("An update operation is already running"); return; }
        executor.execute(()-> {
            try { work.run(token); }
            catch(Exception e) { terminal(token,()->listener.onError(operation.cancelled() ? "Update cancelled" : safeError(e))); }
        });
    }
    private static String safeError(Exception e) {
        // Do not surface network redirect URLs or filesystem paths in UI/logs.
        if(e instanceof IllegalArgumentException || e instanceof SecurityException) return e.getMessage();
        return "Update failed ("+e.getClass().getSimpleName()+"). Check the connection and try again.";
    }
    private void terminal(int token,Runnable r) { main.post(()-> { if(operation.finish(token)) r.run(); }); }
    private void post(int token,Runnable r) { main.post(()-> { if(operation.current(token)) r.run(); }); }
    private void ensureActive() throws java.io.IOException { if(operation.cancelled() || Thread.currentThread().isInterrupted()) throw new java.io.IOException("Cancelled"); }
    private PackageInfo installed() throws Exception {
        PackageInfo info=context.getPackageManager().getPackageInfo(context.getPackageName(),PackageManager.GET_SIGNING_CERTIFICATES);
        verifySigner(info);
        return info;
    }
    private UpdateManifest verify(byte[] envelope) throws Exception { return verify(envelope,false); }
    private UpdateManifest verify(byte[] envelope,boolean allowCurrent) throws Exception {
        PackageInfo current=installed();
        UpdateManifest manifest=allowCurrent
                ? UpdateManifest.verifyForCheck(envelope,current.versionName,(int)current.getLongVersionCode(),PROTOCOL)
                : UpdateManifest.verify(envelope,current.versionName,(int)current.getLongVersionCode(),PROTOCOL);
        synchronized(FLOOR_LOCK) {
            ensureActive();
            android.content.SharedPreferences prefs=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
            int floor=prefs.getInt("highest_code",0);
            String floorVersion=prefs.getString("highest_version","0.0.0");
            manifest.requireNotReplayed(floor,floorVersion);
            if(manifest.versionCode>floor || UpdateManifest.compareVersions(manifest.version,floorVersion)>0) {
                if(!prefs.edit().putInt("highest_code",manifest.versionCode).putString("highest_version",manifest.version).commit()) throw new SecurityException("Could not save update rollback protection");
            }
        }
        return manifest;
    }
    public void check(Listener listener) {
        execute(listener, token->{
            installed();
            post(token,()->listener.onStatus("Checking signed GitHub release…"));
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            fetch(UpdateManifest.REPOSITORY+"/releases/latest/download/update-manifest.txt",bytes,UpdateManifest.MAX_MANIFEST,-1);
            byte[] envelope=bytes.toByteArray(); UpdateManifest manifest=verify(envelope,true);
            PackageInfo current=installed();
            if(manifest.isCurrent(current.versionName,(int)current.getLongVersionCode())) terminal(token,()->listener.onStatus("metuxa is up to date."));
            else terminal(token,()->listener.onRelease(new Release(manifest,envelope)));
        });
    }
    /** Call from an explicit UI install action. Permission is requested separately, never silently. */
    public void downloadAndInstall(Activity activity, Release release, Listener listener) {
        if(activity.isFinishing() || activity.isDestroyed()) return;
        if(needsInstallPermission()) { listener.onError("Allow metuxa to install updates in Android settings, then retry"); return; }
        execute(listener,token->{
            UpdateManifest manifest=verify(release.envelope);
            if(!java.util.Arrays.asList(android.os.Build.SUPPORTED_ABIS).contains("arm64-v8a")) throw new SecurityException("This release requires an arm64-v8a device");
            post(token,()->listener.onStatus("Downloading verified release "+manifest.version+"…"));
            File apk=stage(manifest);
            int sessionId=-1; boolean committed=false;
            try {
                ensureActive();
                verifyArchive(apk,manifest);
                verify(release.envelope); // Recheck installed version after the download.
                synchronized(INSTALL_LOCK) {
                ensureActive();
                PackageInstaller installer=context.getPackageManager().getPackageInstaller();
                // A prior process may have died before committing its session. No OS
                // confirmation exists for these; recover them under the process-wide lock.
                for(PackageInstaller.SessionInfo abandoned:installer.getMySessions()) {
                    if(!abandoned.isCommitted() && context.getPackageName().equals(abandoned.getAppPackageName())) installer.abandonSession(abandoned.getSessionId());
                }
                int pending=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getInt("session",-1);
                if(pending>=0 && installer.getSessionInfo(pending)!=null) throw new IllegalArgumentException("An Android installation is already pending. Complete or cancel it first.");
                PackageInstaller.SessionParams params=new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(context.getPackageName()); params.setSize(manifest.androidSize);
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
                sessionId=installer.createSession(params);
                try(PackageInstaller.Session session=installer.openSession(sessionId); InputStream input=new FileInputStream(apk); OutputStream output=session.openWrite("base.apk",0,manifest.androidSize)) {
                    byte[] buffer=new byte[65536]; int n; long copied=0;
                    MessageDigest copiedHash=MessageDigest.getInstance("SHA-256");
                    while((n=input.read(buffer))!=-1) {
                        ensureActive(); copied+=n;
                        if(copied>manifest.androidSize)throw new SecurityException("APK changed while preparing installation");
                        copiedHash.update(buffer,0,n); output.write(buffer,0,n);
                    }
                    if(copied!=manifest.androidSize || !hex(copiedHash.digest()).equals(manifest.androidSha256)) throw new SecurityException("APK changed while preparing installation");
                    session.fsync(output);
                }
                ensureActive();
                Intent result=new Intent(context,UpdateInstallReceiver.class).setAction(context.getPackageName()+".UPDATE_RESULT");
                PendingIntent callback=PendingIntent.getBroadcast(context,sessionId,result,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_MUTABLE);
                if(!context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putInt("session",sessionId).putString("status","Waiting for Android installation confirmation").commit()) throw new SecurityException("Could not save Android installation state");
                ensureActive();
                try(PackageInstaller.Session session=installer.openSession(sessionId)) {
                    synchronized(operation) {
                        ensureActive();
                        session.commit(callback.getIntentSender()); committed=true;
                    }
                }
                }
                terminal(token,()->listener.onStatus("Confirm the update in Android. Pairings and settings will be kept."));
            } finally {
                if(sessionId>=0 && !committed) { try { context.getPackageManager().getPackageInstaller().abandonSession(sessionId); } catch(Exception ignored) { } }
                if(!apk.delete()) apk.deleteOnExit();
            }
        });
    }
    /** Download and verify only; never requests installer permission or starts installation. */
    public void download(Release release, Listener listener) {
        execute(listener,token->{
            UpdateManifest manifest=verify(release.envelope);
            post(token,()->listener.onStatus("Downloading release "+manifest.version+"…"));
            stage(manifest);
            terminal(token,()->listener.onStatus("Update downloaded and verified. Choose Install to continue."));
        });
    }
    private File stage(UpdateManifest manifest) throws Exception {
        if(!java.util.Arrays.asList(android.os.Build.SUPPORTED_ABIS).contains("arm64-v8a")) throw new SecurityException("This release requires an arm64-v8a device");
        File ready=new File(context.getCacheDir(),"metuxa-update-ready-"+manifest.version+"-"+manifest.androidSha256+".apk");
        if(ready.isFile() && ready.length()==manifest.androidSize && digest(ready).equals(manifest.androidSha256)) {
            ensureActive(); verifyArchive(ready,manifest); ensureActive(); return ready;
        }
        File temporary=File.createTempFile("metuxa-update-", ".apk",context.getCacheDir());
        try {
            try(FileOutputStream out=new FileOutputStream(temporary)) { fetch(manifest.apkUrl(),out,manifest.androidSize,manifest.androidSize); out.getFD().sync(); }
            ensureActive();
            if(!digest(temporary).equals(manifest.androidSha256)) throw new SecurityException("APK checksum does not match signed release");
            verifyArchive(temporary,manifest);
            ensureActive();
            android.system.Os.rename(temporary.getAbsolutePath(),ready.getAbsolutePath());
            return ready;
        } finally { if(temporary.exists() && !temporary.delete()) temporary.deleteOnExit(); }
    }
    private void verifyArchive(File apk,UpdateManifest manifest) throws Exception {
        PackageInfo archive=context.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(),PackageManager.GET_SIGNING_CERTIFICATES);
        if(archive==null || !"dev.metuxa.quest".equals(archive.packageName) || archive.getLongVersionCode()!=manifest.versionCode || !manifest.version.equals(archive.versionName)) throw new SecurityException("APK identity/version does not match signed release");
        verifySigner(archive);
    }
    private static void verifySigner(PackageInfo info) throws Exception {
        if(info.signingInfo==null || info.signingInfo.hasMultipleSigners()) throw new SecurityException("Unexpected APK signer");
        android.content.pm.Signature[] signatures=info.signingInfo.getApkContentsSigners();
        if(signatures.length!=1 || !hex(MessageDigest.getInstance("SHA-256").digest(signatures[0].toByteArray())).equals(UpdateManifest.CERT_SHA256)) throw new SecurityException("Updates require the official metuxa release signing identity");
    }
    private static String digest(File file) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256"); byte[] buffer=new byte[65536]; int n;
        try(InputStream in=new FileInputStream(file)) { while((n=in.read(buffer))!=-1) digest.update(buffer,0,n); }
        return hex(digest.digest());
    }
    private static String hex(byte[] bytes) { StringBuilder result=new StringBuilder(); for(byte b:bytes) result.append(String.format(java.util.Locale.ROOT,"%02x",b&255)); return result.toString(); }
    private static boolean allowed(URL url) {
        if(!"https".equals(url.getProtocol()) || url.getUserInfo()!=null || (url.getPort()!=-1 && url.getPort()!=443) || url.getRef()!=null) return false;
        String host=url.getHost(); return host.equals("github.com") || host.equals("release-assets.githubusercontent.com") || host.equals("objects.githubusercontent.com");
    }
    private void fetch(String initial,OutputStream output,long maximum,long expected) throws Exception {
        URL url=new URL(initial); long deadline=android.os.SystemClock.elapsedRealtime()+10*60*1000L;
        for(int redirect=0;redirect<=5;redirect++) {
            ensureActive(); if(android.os.SystemClock.elapsedRealtime()>deadline) throw new java.io.IOException("Download deadline exceeded");
            if(!allowed(url)) throw new SecurityException("Untrusted release redirect");
            HttpsURLConnection c=(HttpsURLConnection)url.openConnection(); connection=c;
            try {
                c.setInstanceFollowRedirects(false); c.setConnectTimeout(15000); c.setReadTimeout(15000);
                c.setRequestProperty("Accept-Encoding","identity"); c.setRequestProperty("User-Agent","metuxa-updater/1");
                int status=c.getResponseCode();
                if(status==301 || status==302 || status==303 || status==307 || status==308) {
                    String location=c.getHeaderField("Location"); if(location==null || redirect==5) throw new SecurityException("Too many release redirects");
                    url=new URL(url,location); continue;
                }
                if(status!=200) throw new java.io.IOException("Release unavailable");
                String encoding=c.getContentEncoding(); if(encoding!=null && !encoding.equals("identity")) throw new SecurityException("Unsupported release encoding");
                long declared=c.getContentLengthLong(); if(declared>maximum || (expected>=0 && declared>=0 && declared!=expected)) throw new SecurityException("Invalid release size");
                long received=0; byte[] buffer=new byte[65536]; int n;
                try(InputStream in=c.getInputStream()) {
                    while((n=in.read(buffer))!=-1) {
                        ensureActive(); if(android.os.SystemClock.elapsedRealtime()>deadline) throw new java.io.IOException("Download deadline exceeded");
                        received+=n; if(received>maximum) throw new SecurityException("Release exceeds size limit"); output.write(buffer,0,n);
                    }
                }
                if(expected>=0 && received!=expected) throw new SecurityException("Incomplete release download"); return;
            } finally { c.disconnect(); if(connection==c) connection=null; }
        }
        throw new SecurityException("Too many release redirects");
    }
    public void cancel() { operation.cancel(); HttpsURLConnection c=connection; if(c!=null)c.disconnect(); }
    @Override public synchronized void close() { operation.close(); cancel(); main.removeCallbacksAndMessages(null); executor.shutdownNow(); }
}
