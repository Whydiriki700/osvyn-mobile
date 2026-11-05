package id.riki.osvyn.mobile;

import android.content.Context;
import android.graphics.Typeface;

/** Poppins, the Osvyn brand font (same as the desktop build). */
public final class Fonts {
    private static Typeface bold;
    private static Typeface semibold;

    public static Typeface bold(Context ctx) {
        if (bold == null) {
            try {
                bold = Typeface.createFromAsset(ctx.getAssets(), "fonts/poppins-bold.ttf");
            } catch (Exception e) {
                bold = Typeface.create("sans-serif", Typeface.BOLD);
            }
        }
        return bold;
    }

    public static Typeface semibold(Context ctx) {
        if (semibold == null) {
            try {
                semibold = Typeface.createFromAsset(ctx.getAssets(), "fonts/poppins-semibold.ttf");
            } catch (Exception e) {
                semibold = Typeface.create("sans-serif", Typeface.BOLD);
            }
        }
        return semibold;
    }
}
