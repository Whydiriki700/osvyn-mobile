package id.riki.osvyn.mobile;

import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.HapticFeedbackConstants;
import android.view.View;

/**
 * Shared touch feedback: pressed-state backgrounds and light haptics,
 * so every tap in the app answers instantly (Riki's responsiveness
 * pass, 2026-11-03). A control that visibly reacts the moment it is
 * touched feels fast even while the next screen is still loading.
 */
public final class Ui {

    private Ui() {
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
