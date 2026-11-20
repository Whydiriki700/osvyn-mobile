package id.riki.osvyn.mobile;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.util.List;

/**
 * Keep-alive for the message watcher (Riki, 2026-11-03). A light
 * foreground service in the main process with a quiet persistent
 * notification: it keeps Osvyn's processes from being swept away
 * while profiles are being watched for new Muse messages.
 *
 * Honest limit, stated to Riki before building: if the user force
 * stops the app or the system kills everything anyway, watching
 * stops. Muse offers no official push channel to third-party apps.
 */
public class WatchService extends Service {

    public static void start(Context ctx) {
        Intent i = new Intent(ctx, WatchService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Exception ignored) {
            // Starting a foreground service from the background can be
            // refused on newer Android; the next foreground open of the
            // app starts it again.
        }
    }

    // Manual freeze heartbeat (1.5.0): while the Hub's "freeze other
    // profiles" switch is on, this service (always alive in the main
    // process) refreshes the shared call marker under the reserved
    // owner "hub-manual", so background profiles stay frozen until
    // the switch is turned off. If the service dies, the marker goes
    // stale and everyone unfreezes on their own within seconds.
    private final Handler freezeHandler = new Handler(Looper.getMainLooper());
    private final Runnable freezeTick = new Runnable() {
        @Override public void run() {
            try {
                if (HubState.manualFreeze(WatchService.this)) {
                    CallMode.setActive(WatchService.this, "hub-manual", true);
                } else if ("hub-manual".equals(CallMode.freshOwner(WatchService.this))) {
                    CallMode.setActive(WatchService.this, "hub-manual", false);
                }
            } catch (Exception ignored) {
            }
            freezeHandler.postDelayed(this, 5000);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Notify.ensureChannels(this);
        startForeground(Notify.WATCHER_ID, buildNotification());
        freezeHandler.postDelayed(freezeTick, 5000);
    }

    @Override
    public void onDestroy() {
        freezeHandler.removeCallbacks(freezeTick);
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Refresh the watched count whenever a profile opens.
        try {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(Notify.WATCHER_ID, buildNotification());
        } catch (Exception ignored) {
        }
        return START_STICKY;
    }

    private Notification buildNotification() {
        int watched = 0;
        try {
            List<ProfileStore.Profile> all = ProfileStore.load(this);
            for (ProfileStore.Profile p : all) {
                if (p.opened > 0) watched++;
            }
        } catch (Exception ignored) {
        }
        String text = watched == 1
                ? "Watching 1 opened profile for new messages"
                : "Watching " + watched + " opened profiles for new messages";
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, Notify.CH_WATCHER)
                .setSmallIcon(R.drawable.ic_stat_muse)
                .setContentTitle("Osvyn watcher active")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_LOW)
                .build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
