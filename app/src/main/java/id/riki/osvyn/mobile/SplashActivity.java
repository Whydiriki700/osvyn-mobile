package id.riki.osvyn.mobile;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.hardware.fingerprint.FingerprintManager;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.Button;
import android.widget.EditText;
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
    private boolean lockPassed = false;
    private boolean lockShowing = false;
    private CancellationSignal fpCancel;
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

        LinearLayout colSplash = new LinearLayout(this);
        colSplash.setOrientation(LinearLayout.VERTICAL);
        colSplash.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout.LayoutParams wlp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        wlp.gravity = Gravity.CENTER;
        root.addView(colSplash, wlp);
        colSplash.addView(word);

        // Loading line in the shift-splash language (1.6.0): a thin
        // track under the wordmark that fills while the splash holds,
        // so the opening screen is a designed surface, not a bare logo
        // on white.
        FrameLayout track = new FrameLayout(this);
        android.graphics.drawable.GradientDrawable tg = new android.graphics.drawable.GradientDrawable();
        tg.setColor(0xFFE8E8EE);
        tg.setCornerRadius(dp(99));
        track.setBackground(tg);
        LinearLayout.LayoutParams trlp = new LinearLayout.LayoutParams(dp(120), dp(3));
        trlp.topMargin = dp(26);
        track.setLayoutParams(trlp);
        View fill = new View(this);
        android.graphics.drawable.GradientDrawable fg = new android.graphics.drawable.GradientDrawable();
        fg.setColor(0xFF15B3DA);
        fg.setCornerRadius(dp(99));
        fill.setBackground(fg);
        track.addView(fill, new FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT));
        colSplash.addView(track);
        android.animation.ValueAnimator va = android.animation.ValueAnimator.ofInt(0, (int) (dp(120) * 0.82f));
        va.setStartDelay(200);
        va.setDuration(900);
        va.setInterpolator(new PathInterpolator(0.22f, 1f, 0.36f, 1f));
        va.addUpdateListener(anim -> {
            FrameLayout.LayoutParams fl = (FrameLayout.LayoutParams) fill.getLayoutParams();
            fl.width = (int) anim.getAnimatedValue();
            fill.setLayoutParams(fl);
        });
        va.start();

        // Entrance animation, matching desktop "masuk" keyframes.
        if (Ui.motionOk(this)) {
            colSplash.setAlpha(0f);
            colSplash.setScaleX(0.97f);
            colSplash.setScaleY(0.97f);
            colSplash.setTranslationY(dp(8));
            colSplash.animate()
                    .alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                    .setStartDelay(60)
                    .setDuration(320)
                    .setInterpolator(new PathInterpolator(0.22f, 1f, 0.36f, 1f))
                    .start();
        }

        root.setOnClickListener(v -> proceed());
        setContentView(root);

        handler.postDelayed(go, 750);
    }

    private void proceed() {
        if (done) return;
        // App lock (1.5.0): a set PIN gates the Hub. Fingerprint is
        // offered when the phone supports it; the PIN always works.
        if (!lockPassed && HubState.lockEnabled(this)) {
            showLock();
            return;
        }
        done = true;
        handler.removeCallbacks(go);
        if (fpCancel != null) fpCancel.cancel();
        startActivity(new Intent(this, MainActivity.class));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void showLock() {
        if (lockShowing) return;
        lockShowing = true;
        handler.removeCallbacks(go);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFFFFFFFF);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.gravity = Gravity.CENTER;
        root.addView(col, clp);

        TextView title = new TextView(this);
        title.setText("Osvyn is locked");
        title.setTypeface(Fonts.bold(this));
        title.setTextSize(20);
        title.setTextColor(0xFF101014);
        col.addView(title);

        TextView hint = new TextView(this);
        hint.setText("Enter your 4-6 digit PIN, or use your fingerprint.");
        hint.setTextSize(13);
        hint.setTextColor(0xFF83878F);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(6);
        hint.setLayoutParams(hlp);
        col.addView(hint);

        EditText pin = new EditText(this);
        pin.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pin.setHint("PIN");
        pin.setTextSize(18);
        pin.setGravity(Gravity.CENTER);
        pin.setSingleLine(true);
        pin.setLetterSpacing(0.25f);
        pin.setFilters(new android.text.InputFilter[]{
                new android.text.InputFilter.LengthFilter(6)});
        pin.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        android.graphics.drawable.GradientDrawable pinBg = new android.graphics.drawable.GradientDrawable();
        pinBg.setColor(0xFFFFFFFF);
        pinBg.setCornerRadius(dp(12));
        pinBg.setStroke(dp(1), 0xFFE2E2E8);
        pin.setBackground(pinBg);
        pin.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(210), ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.topMargin = dp(18);
        pin.setLayoutParams(plp);
        col.addView(pin);

        TextView err = new TextView(this);
        err.setTextSize(12.5f);
        err.setTextColor(0xFFB3372F);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.topMargin = dp(8);
        err.setLayoutParams(elp);
        col.addView(err);

        Button open = new Button(this);
        open.setText("Unlock");
        open.setTextSize(14);
        open.setTextColor(0xFFFFFFFF);
        open.setAllCaps(false);
        android.graphics.drawable.GradientDrawable obg = new android.graphics.drawable.GradientDrawable();
        obg.setColor(0xFF2B6FF2);
        obg.setCornerRadius(dp(12));
        open.setBackground(obg);
        open.setPadding(dp(30), dp(13), dp(30), dp(13));
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        olp.topMargin = dp(14);
        open.setLayoutParams(olp);
        open.setOnClickListener(v -> {
            if (HubState.checkPin(this, pin.getText().toString().trim())) {
                lockPassed = true;
                // Record the pass in the shared lock session (1.7.0) so
                // the in-app gates in the Hub and profiles agree this
                // session is unlocked until the next long backgrounding.
                HubState.markLockPassed(this);
                HubState.noteLockForeground(this);
                proceed();
            } else {
                err.setText("Wrong PIN, try again.");
                pin.setText("");
            }
        });
        col.addView(open);

        setContentView(root);
        pin.requestFocus();
        if (Ui.motionOk(this)) {
            col.setAlpha(0f);
            col.setTranslationY(dp(10));
            col.animate().alpha(1f).translationY(0f).setDuration(240)
                    .setInterpolator(new PathInterpolator(0.22f, 1f, 0.36f, 1f)).start();
        }

        // Fingerprint, when the hardware and an enrolled finger exist.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                FingerprintManager fm = (FingerprintManager) getSystemService(Context.FINGERPRINT_SERVICE);
                if (fm != null && fm.isHardwareDetected() && fm.hasEnrolledFingerprints()) {
                    fpCancel = new CancellationSignal();
                    fm.authenticate(null, fpCancel, 0,
                            new FingerprintManager.AuthenticationCallback() {
                                @Override public void onAuthenticationSucceeded(
                                        FingerprintManager.AuthenticationResult result) {
                                    lockPassed = true;
                                    HubState.markLockPassed(SplashActivity.this);
                                    HubState.noteLockForeground(SplashActivity.this);
                                    proceed();
                                }
                                @Override public void onAuthenticationFailed() {
                                    err.setText("Fingerprint not recognized.");
                                }
                                @Override public void onAuthenticationError(
                                        int errorCode, CharSequence errString) {
                                    if (errorCode != FingerprintManager.FINGERPRINT_ERROR_CANCELED) {
                                        err.setText(String.valueOf(errString));
                                    }
                                }
                            }, handler);
                }
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(go);
        if (fpCancel != null) fpCancel.cancel();
        super.onDestroy();
    }
}
