package id.riki.osvyn.mobile;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Opening splash, a faithful port of the desktop splash.html:
 * white screen, "Osvyn" wordmark in Poppins Bold with the cyan dot,
 * entering with a scale/translate fade (cubic-bezier(.22,.8,.3,1)).
 * Tap to skip. On mobile the hold is shorter than the desktop 6 s.
 */
public class SplashActivity extends Activity {

    private boolean done = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable go = this::proceed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(0xFFFFFFFF);
        getWindow().setNavigationBarColor(0xFFFFFFFF);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFFFFFFFF);

        LinearLayout word = new LinearLayout(this);
        word.setOrientation(LinearLayout.HORIZONTAL);
        word.setGravity(Gravity.CENTER | Gravity.BOTTOM);

        TextView name = new TextView(this);
        name.setText("Osvyn");
        name.setTypeface(Fonts.bold(this));
        name.setTextSize(64);
        name.setTextColor(0xFF101014);
        name.setLetterSpacing(-0.02f);
        word.addView(name);

        TextView dot = new TextView(this);
        int d = (int) (13 * getResources().getDisplayMetrics().density + 0.5f);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(d, d);
        dlp.leftMargin = (int) (5 * getResources().getDisplayMetrics().density + 0.5f);
        dlp.bottomMargin = (int) (11 * getResources().getDisplayMetrics().density + 0.5f);
        dot.setLayoutParams(dlp);
        android.graphics.drawable.GradientDrawable circle = new android.graphics.drawable.GradientDrawable();
        circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        circle.setColor(0xFF15B3DA);
        dot.setBackground(circle);
        word.addView(dot);

        FrameLayout.LayoutParams wlp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        wlp.gravity = Gravity.CENTER;
        root.addView(word, wlp);

        // Entrance animation, matching desktop "masuk" keyframes.
        word.setAlpha(0f);
        word.setScaleX(0.96f);
        word.setScaleY(0.96f);
        word.setTranslationY(8 * getResources().getDisplayMetrics().density);
        word.animate()
                .alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                .setStartDelay(100)
                .setDuration(800)
                .setInterpolator(new PathInterpolator(0.22f, 0.8f, 0.3f, 1f))
                .start();

        root.setOnClickListener(v -> proceed());
        setContentView(root);

        handler.postDelayed(go, 1150);
    }

    private void proceed() {
        if (done) return;
        done = true;
        handler.removeCallbacks(go);
        startActivity(new Intent(this, MainActivity.class));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(go);
        super.onDestroy();
    }
}
