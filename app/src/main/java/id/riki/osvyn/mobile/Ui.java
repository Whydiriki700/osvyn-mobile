package id.riki.osvyn.mobile;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.provider.Settings;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

/**
 * Shared touch feedback: pressed-state backgrounds and light haptics,
 * so every tap in the app answers instantly (Riki's responsiveness
 * pass, 2026-11-03). A control that visibly reacts the moment it is
 * touched feels fast even while the next screen is still loading.
 */
public final class Ui {

    private Ui() {
    }

    /**
     * The locked motion token for the whole app (Master's standing
     * rule): ease-out cubic-bezier(0.22, 1, 0.36, 1), durations in the
     * 120-320 ms band, no overshoot. Every animation added or changed
     * in 1.6.0 uses this curve.
     */
    public static final Interpolator EASE = new PathInterpolator(0.22f, 1f, 0.36f, 1f);

    /** True when the system animator duration scale allows motion. */
    public static boolean motionOk(Context ctx) {
        try {
            return Settings.Global.getFloat(ctx.getContentResolver(),
                    Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f;
        } catch (Exception e) {
            return true;
        }
    }

    public static GradientDrawable round(int color, int radiusPx, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        if (strokeColor != 0) g.setStroke(2, strokeColor);
        return g;
    }

    public static StateListDrawable press(int normal, int pressed, int radiusPx, int strokeColor) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, round(pressed, radiusPx, strokeColor));
        s.addState(new int[]{}, round(normal, radiusPx, strokeColor));
        return s;
    }

    public static void haptic(View v) {
        try {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        } catch (Exception ignored) {
            // Haptics are garnish; never let them break a tap.
        }
    }
}
