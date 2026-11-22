package id.riki.osvyn.mobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;

/**
 * Message notifications (Riki, 2026-11-03): when a watched Muse
 * profile sees a new incoming message, post a sounding notification
 * whose tap opens that exact profile.
 */
public final class Notify {

    public static final String CH_MESSAGES = "muse_messages";
    public static final String CH_WATCHER = "watcher";
    public static final int WATCHER_ID = 9001;

    private Notify() {
    }

    public static void ensureChannels(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        NotificationChannel msgs = new NotificationChannel(
                CH_MESSAGES, "Muse messages", NotificationManager.IMPORTANCE_HIGH);
        msgs.setDescription("New incoming messages in your Muse profiles");
        Uri sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        msgs.setSound(sound, new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build());
        msgs.enableVibration(true);
        msgs.setVibrationPattern(new long[]{0, 180, 120, 180});
        msgs.enableLights(true);
        nm.createNotificationChannel(msgs);

        NotificationChannel watcher = new NotificationChannel(
                CH_WATCHER, "Watcher", NotificationManager.IMPORTANCE_LOW);
        watcher.setDescription("Osvyn is watching your opened profiles for new messages");
        watcher.setSound(null, null);
        watcher.enableVibration(false);
        nm.createNotificationChannel(watcher);
    }

    public static int notifIdForSlot(int slot) {
        return 1000 + slot;
    }

    public static void postMessage(Context ctx, int slot, String profileId,
                                   String profileName, int count, String snippet) {
        ensureChannels(ctx);
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        Intent i = new Intent(ctx, ProfileStore.activityClassForSlot(slot));
        i.putExtra(BaseProfileActivity.EXTRA_PROFILE_ID, profileId);
        i.putExtra(BaseProfileActivity.EXTRA_PROFILE_NAME, profileName);
        i.putExtra(BaseProfileActivity.EXTRA_SLOT, slot);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(ctx, slot, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String text = count > 1
                ? count + " unread messages in Muse"
                : "New message in Muse";
        if (snippet != null && !snippet.trim().isEmpty()) {
            text = snippet.trim();
        }

        Notification n = new Notification.Builder(ctx, CH_MESSAGES)
                .setSmallIcon(R.drawable.ic_stat_muse)
                .setContentTitle(profileName + " · Muse")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setPriority(Notification.PRIORITY_HIGH)
                .setDefaults(Notification.DEFAULT_SOUND | Notification.DEFAULT_VIBRATE
                        | Notification.DEFAULT_LIGHTS)
                .build();
        nm.notify(notifIdForSlot(slot), n);
    }

    public static void cancelForSlot(Context ctx, int slot) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(notifIdForSlot(slot));
    }
}
