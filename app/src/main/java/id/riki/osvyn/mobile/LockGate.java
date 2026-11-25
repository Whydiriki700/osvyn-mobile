package id.riki.osvyn.mobile;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * In-app app-lock gate (1.7.0).
 *
 * Before 1.7.0 the PIN was only asked on the Splash screen at cold
 * start. Re-entering from recents, or opening an already running
 * profile, skipped Splash entirely, so a switched-on lock had no
 * visible effect. This gate is enforced from onResume of the Hub and
 * of every profile: when the lock is on and the app has been in the
 * background for over a minute since the last successful PIN entry,
 * an opaque PIN screen covers the activity until the PIN is entered.
 *
 * The session facts (last pass, last backgrounding, last foreground)
 * live in HubState's shared file, so every process of the app agrees
 * on whether the lock is currently satisfied. Splash keeps its own
 * cold-start gate and records the pass into the same session state.
 */
public final class LockGate {

    private static final long RELOCK_AFTER_MS = 60_000L;
    private static final String TAG = "osvyn_lock_gate";

    private LockGate() {}

    /** True when the lock is on and a PIN must be asked right now. */
    static boolean required(Context ctx) {
        if (!HubState.lockEnabled(ctx)) return false;
        long now = System.currentTimeMillis();
        long passed = HubState.lockPassedAt(ctx);
        long bg = HubState.lockBackgroundAt(ctx);
        long fg = HubState.lockForegroundAt(ctx);
        // Never backgrounded since the pass (or never backgrounded at
        // all): the session is still the one that passed, or the cold
        // start gate in Splash is the one responsible.
        if (bg <= passed) return false;
        // Another activity already came to the foreground after that
        // backgrounding and was not locked then; the session flows on.
        if (fg > bg) return false;
        return now - bg > RELOCK_AFTER_MS;
    }

    static boolean isShowing(Activity act) {
        ViewGroup content = act.findViewById(android.R.id.content);
        return content != null && content.findViewWithTag(TAG) != null;
    }

    /**
     * If the lock is required, covers the activity with the PIN screen
     * and returns true. The caller stamps the foreground fact only
     * when this returns false, so an activity recreated while locked
     * (rotation, theme change) asks again instead of slipping past.
     */
    static boolean enforceIfNeeded(Activity act) {
        if (!required(act)) return false;
        if (isShowing(act)) return true;

        ViewGroup content = act.findViewById(android.R.id.content);
        if (content == null) return false;

        FrameLayout root = new FrameLayout(act);
        root.setTag(TAG);
        root.setBackgroundColor(0xFFFFFFFF);
        root.setClickable(true);

        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.gravity = Gravity.CENTER;
        root.addView(col, clp);

        TextView title = new TextView(act);
        title.setText("Osvyn is locked");
        title.setTypeface(Fonts.bold(act));
        title.setTextSize(20);
        title.setTextColor(0xFF101014);
        col.addView(title);

        TextView hint = new TextView(act);
        hint.setText("Enter your PIN to keep going.");
        hint.setTextSize(13);
        hint.setTextColor(0xFF5A5766);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(act, 6);
        hint.setLayoutParams(hlp);
        col.addView(hint);

        EditText pin = new EditText(act);
        pin.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pin.setHint("PIN");
        pin.setTextSize(18);
        pin.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                dp(act, 180), ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.topMargin = dp(act, 18);
        pin.setLayoutParams(plp);
        col.addView(pin);

        TextView err = new TextView(act);
        err.setTextSize(12.5f);
        err.setTextColor(0xFFC81E1E);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.topMargin = dp(act, 8);
        err.setLayoutParams(elp);
        col.addView(err);

        Button open = new Button(act);
        open.setText("Open");
        open.setTextSize(14);
        open.setTextColor(Color.WHITE);
        open.setAllCaps(false);
        open.setTypeface(Fonts.semibold(act));
        android.graphics.drawable.GradientDrawable obg =
                new android.graphics.drawable.GradientDrawable();
        obg.setColor(0xFF2B6FF2);
        obg.setCornerRadius(dp(act, 12));
        open.setBackground(obg);
        open.setPadding(dp(act, 30), dp(act, 13), dp(act, 30), dp(act, 13));
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        olp.topMargin = dp(act, 14);
        open.setLayoutParams(olp);
        open.setOnClickListener(v -> {
            if (HubState.checkPin(act, pin.getText().toString().trim())) {
                HubState.markLockPassed(act);
                HubState.noteLockForeground(act);
                content.removeView(root);
            } else {
                err.setText("Wrong PIN, try again.");
                pin.setText("");
            }
        });
        col.addView(open);

        content.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        pin.requestFocus();
        if (Ui.motionOk(act)) {
            col.setAlpha(0f);
            col.setTranslationY(dp(act, 10));
            col.animate().alpha(1f).translationY(0f).setDuration(240)
                    .setInterpolator(Ui.EASE).start();
        }
        return true;
    }

    private static int dp(Context ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
