package com.metashare.client;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

/** Explicit mutable PendingIntent target, not exported to other applications. */
public final class UpdateInstallReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent) {
        if(! (context.getPackageName()+".UPDATE_RESULT").equals(intent.getAction())) return;
        android.content.SharedPreferences prefs=context.getSharedPreferences(UpdateManager.PREFS,Context.MODE_PRIVATE);
        int session=intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID,-1);
        if(session<0 || session!=prefs.getInt("session",-2)) return;
        int status=intent.getIntExtra(PackageInstaller.EXTRA_STATUS,PackageInstaller.STATUS_FAILURE);
        String message;
        boolean awaitingUser=false;
        if(status==PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm=android.os.Build.VERSION.SDK_INT >= 33
                    ? intent.getParcelableExtra(Intent.EXTRA_INTENT,Intent.class)
                    : intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if(ConnectionCoordinator.INSTANCE.current()!=null) message="Update installation deferred. Disconnect your screens and retry the update.";
            else if(confirm!=null) {
                try { confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); context.startActivity(confirm); awaitingUser=true; message="Waiting for Android installation confirmation"; }
                catch(RuntimeException e) { message="Android could not open installation confirmation. Retry the update from metuxa."; }
            } else message="Android installation confirmation is unavailable. Retry the update.";
        } else if(status==PackageInstaller.STATUS_SUCCESS) message="Update installed successfully";
        else if(status==PackageInstaller.STATUS_FAILURE_ABORTED) message="Update cancelled. Your current app and data are unchanged.";
        else if(status==PackageInstaller.STATUS_FAILURE_BLOCKED) message="Android blocked the installation. Check install permissions and retry.";
        else if(status==PackageInstaller.STATUS_FAILURE_STORAGE) message="Not enough storage to install the update";
        else if(status==PackageInstaller.STATUS_FAILURE_INCOMPATIBLE) message="This update is incompatible with your device";
        else message="Android could not install the update. Your current app and data are unchanged.";
        android.content.SharedPreferences.Editor edit=prefs.edit().putString("status",message);
        if(status==PackageInstaller.STATUS_PENDING_USER_ACTION && !awaitingUser) {
            try { context.getPackageManager().getPackageInstaller().abandonSession(session); } catch(RuntimeException ignored) { }
        }
        if(!awaitingUser) edit.remove("session");
        edit.apply();
    }
}
