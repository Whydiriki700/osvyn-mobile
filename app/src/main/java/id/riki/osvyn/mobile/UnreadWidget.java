package id.riki.osvyn.mobile;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

/**
 * Home screen widget (1.5.0): the total unread Muse messages across
 * all profiles, readable without opening the app. The count comes
 * from the shared HubState the profile watchers write; tapping the
 * widget opens the Hub. Profile processes call refresh() whenever a
 * count changes, and the system also updates it on its own period.
 */
public class UnreadWidget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        for (int id : ids) {
            mgr.updateAppWidget(id, views(ctx));
        }
    }

    static RemoteViews views(Context ctx) {
        int total = HubState.totalUnread(ctx);
        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget_unread);
        v.setTextViewText(R.id.widget_count, String.valueOf(total));
        v.setTextViewText(R.id.widget_label,
                total == 1 ? "unread message" : "unread messages");
        Intent i = new Intent(ctx, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        v.setOnClickPendingIntent(R.id.widget_root, pi);
        return v;
    }

    /** Push a fresh count to every placed widget right now. */
    public static void refresh(Context ctx) {
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
            int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, UnreadWidget.class));
            for (int id : ids) {
                mgr.updateAppWidget(id, views(ctx));
            }
        } catch (Exception ignored) {
        }
    }
}
