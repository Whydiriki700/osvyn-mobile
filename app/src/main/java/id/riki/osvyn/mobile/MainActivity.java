package id.riki.osvyn.mobile;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Osvyn Mobile home: a port of the desktop Hub page (hub.html).
 * Light surface #FBFBFC, white cards with #E2E2E8 borders and 14dp radius,
 * AURA avatars with presence dots, Poppins brand type, blue primary button,
 * and the desktop stats strip. This activity never creates a WebView, so
 * the main process stays clean for the per-profile processes.
 */
public class MainActivity extends Activity {

    // Desktop Hub palette
    private static final int INK = 0xFF101014;
    private static final int INK2 = 0xFF3C3F47;
    private static final int INK3 = 0xFF83878F;
    private static final int GARIS = 0xFFE2E2E8;
    private static final int BG = 0xFFFBFBFC;
    private static final int PANEL = 0xFFFFFFFF;
    private static final int BLUE = 0xFF2B6FF2;
    private static final int BLUE_DARK = 0xFF1E56C8;
    private static final int CYAN = 0xFF15B3DA;
    private static final int GREEN = 0xFF1D9E57;
    private static final int GREEN_BG = 0xFFE5F6EC;
    private static final int GRAY_BG = 0xFFF1F1F4;
    private static final int YELLOW = 0xFF8A5C00;
    private static final int YELLOW_BG = 0xFFFDF3E0;
    private static final int RED = 0xFFB3372F;
    private static final int RED_BG = 0xFFFDECEA;

    private LinearLayout listBox;
    private TextView statsText;
    private List<ProfileStore.Profile> profiles = new ArrayList<>();
    private boolean shiftShowing = false;

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable round(int color, int radiusDp, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        if (strokeColor != 0) g.setStroke(dp(1), strokeColor);
        return g;
    }

    private TextView tv(String text, float sp, int color, Typeface tf) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (tf != null) t.setTypeface(tf);
        return t;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Notify.ensureChannels(this);
        WatchService.start(this);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            try {
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 7001);
            } catch (Exception ignored) {
            }
        }
        getWindow().setStatusBarColor(0xFFFFFFFF);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(18), dp(20), dp(28));
        scroll.addView(root);

        // Brand row: Osvyn. wordmark like the Hub sidebar
        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.HORIZONTAL);
        brand.setGravity(Gravity.BOTTOM);
        TextView word = tv("Osvyn", 21, INK, Fonts.bold(this));
        word.setLetterSpacing(-0.02f);
        brand.addView(word);
        TextView dot = new TextView(this);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(7), dp(7));
        dlp.leftMargin = dp(2);
        dlp.bottomMargin = dp(5);
        dot.setLayoutParams(dlp);
        GradientDrawable dc = new GradientDrawable();
        dc.setShape(GradientDrawable.OVAL);
        dc.setColor(CYAN);
        dot.setBackground(dc);
        brand.addView(dot);
        root.addView(brand);
        root.addView(tv("Multi-account manager for Muse", 12, INK3, null));

        // Header row: H1 + primary action
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(20);
        head.setLayoutParams(hlp);
        TextView h1 = tv("Hub", 24, INK, Fonts.bold(this));
        h1.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(h1);
        Button add = new Button(this);
        add.setText("+ New profile");
        add.setTextSize(13);
        add.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        add.setTextColor(Color.WHITE);
        add.setBackground(Ui.press(BLUE, BLUE_DARK, dp(9), 0));
        add.setPadding(dp(15), dp(9), dp(15), dp(9));
        add.setAllCaps(false);
        add.setOnClickListener(v -> {
            Ui.haptic(v);
            v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(80)
                    .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()).start();
            showAddDialog();
        });
        head.addView(add);
        root.addView(head);

        TextView sub = tv("All your Muse profiles in one place. Open, put to sleep, or manage them without switching tabs one by one.", 13.5f, INK3, null);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(4);
        sub.setLayoutParams(slp);
        root.addView(sub);

        // Stats strip with hairlines, like the Hub
        statsText = tv("", 13, INK2, null);
        statsText.setPadding(0, dp(13), 0, dp(13));
        LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stlp.topMargin = dp(22);
        statsText.setLayoutParams(stlp);
        View topLine = new View(this);
        topLine.setBackgroundColor(GARIS);
        topLine.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        LinearLayout statsBox = new LinearLayout(this);
        statsBox.setOrientation(LinearLayout.VERTICAL);
        statsBox.setLayoutParams(stlp);
        statsBox.addView(topLine);
        statsBox.addView(statsText);
        View botLine = new View(this);
        botLine.setBackgroundColor(GARIS);
        botLine.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        statsBox.addView(botLine);
        root.addView(statsBox);

        TextView section = tv("PROFILES", 11, INK3, Typeface.create("sans-serif", Typeface.BOLD));
        section.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams seclp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        seclp.topMargin = dp(20);
        seclp.bottomMargin = dp(4);
        section.setLayoutParams(seclp);
        root.addView(section);

        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(listBox);

        TextView note = tv("Sleeping profiles keep their page alive in the background with the renderer parked, so the watcher still hears new messages and reopening is instant. The watcher runs while the app has not been force stopped.", 12.5f, INK3, null);
        note.setLineSpacing(0, 1.35f);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = dp(22);
        note.setLayoutParams(nlp);
        root.addView(note);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            TextView hint = tv("Message notifications are off. Tap here to allow them, so new Muse messages can ring.", 12.5f, BLUE_DARK, Typeface.create("sans-serif", Typeface.BOLD));
            hint.setBackground(Ui.press(YELLOW_BG, 0xFFF7E7C4, dp(9), 0));
            hint.setPadding(dp(12), dp(9), dp(12), dp(9));
            LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hintLp.topMargin = dp(16);
            hint.setLayoutParams(hintLp);
            hint.setOnClickListener(v -> {
                Ui.haptic(v);
                try {
                    requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 7001);
                } catch (Exception ignored) {
                }
            });
            root.addView(hint);
        }

        TextView foot = tv("Osvyn Mobile 1.3.0 - max " + ProfileStore.MAX_PROFILES + " profiles", 11, INK3, null);
        foot.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.topMargin = dp(18);
        foot.setLayoutParams(flp);
        root.addView(foot);

        // Hub entrance animation (hubMasuk)
        root.setAlpha(0f);
        root.setTranslationY(dp(14));
        root.setScaleX(0.985f);
        root.setScaleY(0.985f);
        root.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                .setDuration(340)
                .setInterpolator(new PathInterpolator(0.21f, 0.9f, 0.28f, 1f))
                .start();

        setContentView(scroll);

        if (getIntent() != null && getIntent().getBooleanExtra("open_add", false)) {
            getIntent().removeExtra("open_add");
            root.post(this::showAddDialog);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        finishPendingWipes();
        refresh();
    }

    private void finishPendingWipes() {
        List<ProfileStore.Profile> all = ProfileStore.load(this);
        boolean changed = false;
        List<ProfileStore.Profile> keep = new ArrayList<>();
        for (ProfileStore.Profile p : all) {
            File marker = ProfileStore.wipeMarker(this, p.slot);
            if (marker.exists()) {
                marker.delete();
                if (p.id.startsWith("deleting:")) {
                    changed = true;
                    continue;
                }
            }
            keep.add(p);
        }
        if (changed) ProfileStore.save(this, keep);
    }

    private String relative(long ms) {
        long diff = System.currentTimeMillis() - ms;
        if (diff < 60_000) return "just now";
        if (diff < 3_600_000) return (diff / 60_000) + " min ago";
        if (diff < 86_400_000) return (diff / 3_600_000) + " h ago";
        return (diff / 86_400_000) + " d ago";
    }

    private void refresh() {
        profiles = ProfileStore.load(this);
        int opened = 0;
        for (ProfileStore.Profile p : profiles) if (p.opened > 0) opened++;
        statsText.setText("Profiles: " + profiles.size()
                + "     Opened: " + opened
                + "     Not signed in: " + (profiles.size() - opened));

        listBox.removeAllViews();
        if (profiles.isEmpty()) {
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER);
            box.setBackground(round(PANEL, 16, GARIS));
            box.setPadding(dp(30), dp(32), dp(30), dp(32));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.topMargin = dp(12);
            box.setLayoutParams(blp);
            TextView t = tv("No profiles yet", 18, INK, Fonts.bold(this));
            t.setGravity(Gravity.CENTER);
            box.addView(t);
            TextView d = tv("Create your first profile, then sign in to Muse inside it. Each profile keeps its own separate login session.", 13.5f, INK2, null);
            d.setGravity(Gravity.CENTER);
            d.setLineSpacing(0, 1.4f);
            LinearLayout.LayoutParams dlp2 = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dlp2.topMargin = dp(8);
            d.setLayoutParams(dlp2);
            box.addView(d);
            listBox.addView(box);
            return;
        }
        for (int i = 0; i < profiles.size(); i++) {
            View row = profileRow(profiles.get(i));
            row.setAlpha(0f);
            row.setTranslationY(dp(8));
            row.animate().alpha(1f).translationY(0f).setDuration(380)
                    .setStartDelay(i * 60L).start();
            listBox.addView(row);
        }
    }

    private View profileRow(ProfileStore.Profile p) {
        boolean deleting = p.id.startsWith("deleting:");
        boolean opened = p.opened > 0;

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(round(PANEL, 14, GARIS));
        row.setPadding(dp(16), dp(14), dp(14), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        row.setLayoutParams(lp);

        // Avatar with presence dot
        FrameLayout avatarBox = new FrameLayout(this);
        avatarBox.setLayoutParams(new LinearLayout.LayoutParams(dp(42), dp(42)));
        TextView avatar = new TextView(this);
        avatar.setText(p.name.trim().isEmpty() ? "?" : p.name.trim().substring(0, 1).toUpperCase());
        avatar.setTextColor(Color.WHITE);
        avatar.setTextSize(15);
        avatar.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        avatar.setGravity(Gravity.CENTER);
        GradientDrawable ac = new GradientDrawable();
        ac.setShape(GradientDrawable.OVAL);
        ac.setColor(ProfileStore.AURA[(p.slot - 1) % ProfileStore.AURA.length]);
        avatar.setBackground(ac);
        avatarBox.addView(avatar, new FrameLayout.LayoutParams(dp(42), dp(42)));
        View pdot = new View(this);
        GradientDrawable pd = new GradientDrawable();
        pd.setShape(GradientDrawable.OVAL);
        pd.setColor(opened ? GREEN : 0xFFC9CCD4);
        pd.setStroke(dp(2), Color.WHITE);
        pdot.setBackground(pd);
        FrameLayout.LayoutParams pdlp = new FrameLayout.LayoutParams(dp(11), dp(11));
        pdlp.gravity = Gravity.BOTTOM | Gravity.RIGHT;
        avatarBox.addView(pdot, pdlp);
        row.addView(avatarBox);

        LinearLayout ident = new LinearLayout(this);
        ident.setOrientation(LinearLayout.VERTICAL);
        ident.setPadding(dp(13), 0, dp(8), 0);
        ident.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView name = tv(p.name, 14.5f, INK, Typeface.create("sans-serif", Typeface.BOLD));
        name.setMaxLines(1);
        ident.addView(name);
        String meta = deleting ? "Deleting... reopen it once to finish the wipe"
                : opened ? "Put to sleep by DeepSleep, last opened " + relative(p.opened)
                : "Profile created, not signed in yet";
        TextView metaTv = tv(meta, 12.5f, INK3, null);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = dp(2);
        metaTv.setLayoutParams(mlp);
        ident.addView(metaTv);

        TextView chip = tv(opened ? "Asleep" : "Not signed in", 11.5f,
                opened ? INK2 : YELLOW, Typeface.create("sans-serif", Typeface.BOLD));
        chip.setBackground(round(opened ? GRAY_BG : YELLOW_BG, 99, 0));
        chip.setPadding(dp(10), dp(3), dp(10), dp(3));
        LinearLayout.LayoutParams chlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chlp.topMargin = dp(6);
        chip.setLayoutParams(chlp);
        ident.addView(chip);
        row.addView(ident);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setGravity(Gravity.CENTER);
        Button open = smallButton("Open", PANEL, INK2, GARIS);
        open.setOnClickListener(v -> {
            Ui.haptic(v);
            openProfile(p);
        });
        actions.addView(open);
        Button del = smallButton("Delete", RED_BG, RED, 0xFFEFC4C0);
        LinearLayout.LayoutParams delLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        delLp.topMargin = dp(7);
        del.setLayoutParams(delLp);
        del.setOnClickListener(v -> {
            Ui.haptic(v);
            confirmDelete(p);
        });
        actions.addView(del);
        row.addView(actions);

        // The whole card opens the profile, and it answers the touch
        // with a small press scale so it never feels dead.
        row.setOnTouchListener((v, ev) -> {
            if (ev.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                v.animate().scaleX(0.985f).scaleY(0.985f).setDuration(90).start();
            } else if (ev.getAction() == android.view.MotionEvent.ACTION_UP
                    || ev.getAction() == android.view.MotionEvent.ACTION_CANCEL) {
                v.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
            }
            return false;
        });
        row.setOnClickListener(v -> {
            Ui.haptic(v);
            openProfile(p);
        });
        return row;
    }

    private Button smallButton(String text, int bg, int fg, int stroke) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(12.5f);
        b.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        b.setTextColor(fg);
        int pressedBg = bg == BLUE ? BLUE_DARK : bg == RED_BG ? 0xFFF9DAD6 : 0xFFEDEDF1;
        b.setBackground(Ui.press(bg, pressedBg, dp(9), stroke));
        b.setPadding(dp(12), dp(6), dp(12), dp(6));
        b.setAllCaps(false);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        return b;
    }

    private void openProfile(ProfileStore.Profile p) {
        // Stamp last opened, like the desktop Hub "terakhir" record.
        List<ProfileStore.Profile> all = ProfileStore.load(this);
        for (ProfileStore.Profile x : all) {
            if (x.slot == p.slot) x.opened = System.currentTimeMillis();
        }
        ProfileStore.save(this, all);
        p.opened = System.currentTimeMillis();

        Intent i = new Intent(this, ProfileStore.activityClassForSlot(p.slot));
        i.putExtra(BaseProfileActivity.EXTRA_PROFILE_ID, p.id);
        i.putExtra(BaseProfileActivity.EXTRA_PROFILE_NAME, p.name);
        i.putExtra(BaseProfileActivity.EXTRA_SLOT, p.slot);
        startActivity(i);
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    /**
     * Custom panel in the Hub design language (white card, 16dp radius,
     * Poppins title, rounded input, blue primary button). Replaces the
     * stock Android AlertDialog, which Riki flagged as still looking
     * "classic" next to the redesigned screens.
     */
    private android.app.Dialog panelDialog(String title, String message, EditText input,
                                             String positiveText, boolean positiveDanger,
                                             Runnable onPositive) {
        android.app.Dialog dlg = new android.app.Dialog(this);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(round(PANEL, 16, 0));
        card.setPadding(dp(24), dp(22), dp(24), dp(20));

        TextView t = tv(title, 18, INK, Fonts.bold(this));
        card.addView(t);

        if (message != null) {
            TextView m = tv(message, 13.5f, INK2, null);
            m.setLineSpacing(0, 1.4f);
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            mlp.topMargin = dp(8);
            m.setLayoutParams(mlp);
            card.addView(m);
        }

        if (input != null) {
            input.setTextColor(INK);
            input.setHintTextColor(INK3);
            input.setTextSize(15);
            input.setSingleLine(true);
            input.setBackground(round(PANEL, 9, GARIS));
            input.setPadding(dp(14), dp(11), dp(14), dp(11));
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ilp.topMargin = dp(14);
            input.setLayoutParams(ilp);
            card.addView(input);
        }

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(20);
        row.setLayoutParams(rlp);

        Button cancel = smallButton("Cancel", PANEL, INK2, GARIS);
        cancel.setOnClickListener(v -> dlg.dismiss());
        row.addView(cancel);

        Button ok = positiveDanger
                ? smallButton(positiveText, RED_BG, RED, 0xFFEFC4C0)
                : smallButton(positiveText, BLUE, Color.WHITE, 0);
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        olp.leftMargin = dp(9);
        ok.setLayoutParams(olp);
        ok.setOnClickListener(v -> {
            dlg.dismiss();
            onPositive.run();
        });
        row.addView(ok);
        card.addView(row);

        dlg.setContentView(card);
        if (dlg.getWindow() != null) {
            dlg.getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            int w = (int) (getResources().getDisplayMetrics().widthPixels * 0.88f);
            dlg.getWindow().setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        return dlg;
    }

    private void showAddDialog() {
        if (profiles.size() >= ProfileStore.MAX_PROFILES) {
            Toast.makeText(this, "Profile limit reached (" + ProfileStore.MAX_PROFILES + ")", Toast.LENGTH_LONG).show();
            return;
        }
        EditText input = new EditText(this);
        input.setHint("New profile name");
        android.app.Dialog dlg = panelDialog("New profile", null, input, "Create", false, () -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty()) name = "Profile " + (profiles.size() + 1);
            int slot = ProfileStore.freeSlot(profiles);
            if (slot < 0) {
                Toast.makeText(this, "No free profile slot", Toast.LENGTH_LONG).show();
                return;
            }
            ProfileStore.Profile p = new ProfileStore.Profile(
                    "profil-" + System.currentTimeMillis(), name, slot, slot - 1);
            profiles.add(p);
            ProfileStore.save(this, profiles);
            refresh();
            showShiftSplash(p);
        });
        dlg.show();
        input.requestFocus();
    }

    /**
     * Port of the desktop splash_shift.html: white screen, smaller wordmark,
     * "Preparing new profile" with animated dots and a cyan progress line
     * filling to 82%, then the new profile opens.
     */
    private void showShiftSplash(ProfileStore.Profile p) {
        if (shiftShowing) return;
        shiftShowing = true;
        ViewGroup content = findViewById(android.R.id.content);
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(Color.WHITE);

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout.LayoutParams wlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.gravity = Gravity.CENTER;
        overlay.addView(wrap, wlp);

        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.HORIZONTAL);
        brand.setGravity(Gravity.BOTTOM);
        brand.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // center the brand row inside wrap
        ((LinearLayout.LayoutParams) brand.getLayoutParams()).gravity = Gravity.CENTER_HORIZONTAL;
        TextView word = tv("Osvyn", 44, INK, Fonts.bold(this));
        word.setLetterSpacing(-0.02f);
        brand.addView(word);
        TextView dot = new TextView(this);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(9), dp(9));
        dlp.leftMargin = dp(4);
        dlp.bottomMargin = dp(8);
        dot.setLayoutParams(dlp);
        GradientDrawable dc = new GradientDrawable();
        dc.setShape(GradientDrawable.OVAL);
        dc.setColor(CYAN);
        dot.setBackground(dc);
        brand.addView(dot);
        wrap.addView(brand);

        TextView teks = tv("Preparing new profile", 15, 0xFF3C3C44,
                Typeface.create("sans-serif", Typeface.BOLD));
        teks.setLetterSpacing(0.02f);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(20);
        teks.setLayoutParams(tlp);
        wrap.addView(teks);

        // Progress line: 150dp x 3dp, track #E8E8EE, fill cyan to 82%
        FrameLayout track = new FrameLayout(this);
        track.setBackground(round(0xFFE8E8EE, 99, 0));
        LinearLayout.LayoutParams trlp = new LinearLayout.LayoutParams(dp(150), dp(3));
        trlp.topMargin = dp(20);
        track.setLayoutParams(trlp);
        View fill = new View(this);
        fill.setBackground(round(CYAN, 99, 0));
        track.addView(fill, new FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT));
        wrap.addView(track);

        wrap.setAlpha(0f);
        wrap.setScaleX(0.96f);
        wrap.setScaleY(0.96f);
        wrap.setTranslationY(dp(8));
        wrap.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                .setStartDelay(100).setDuration(800)
                .setInterpolator(new PathInterpolator(0.22f, 0.8f, 0.3f, 1f)).start();

        ValueAnimator va = ValueAnimator.ofInt(0, (int) (dp(150) * 0.82f));
        va.setStartDelay(450);
        va.setDuration(1200);
        va.setInterpolator(new PathInterpolator(0.22f, 0.8f, 0.3f, 1f));
        va.addUpdateListener(a -> {
            FrameLayout.LayoutParams fl = (FrameLayout.LayoutParams) fill.getLayoutParams();
            fl.width = (int) a.getAnimatedValue();
            fill.setLayoutParams(fl);
        });
        va.start();

        Handler h = new Handler(Looper.getMainLooper());
        String base = "Preparing new profile";
        Runnable[] tick = new Runnable[1];
        int[] step = {0};
        tick[0] = () -> {
            String[] dots = {"", ".", "..", "..."};
            teks.setText(base + dots[step[0] % 4]);
            step[0]++;
            if (shiftShowing) h.postDelayed(tick[0], 300);
        };
        h.postDelayed(tick[0], 300);

        Runnable finishShift = () -> {
            if (!shiftShowing) return;
            shiftShowing = false;
            h.removeCallbacks(tick[0]);
            overlay.animate().alpha(0f).setDuration(300).withEndAction(() -> {
                content.removeView(overlay);
                openProfile(p);
            }).start();
        };
        overlay.setOnClickListener(v -> finishShift.run());
        h.postDelayed(finishShift, 1850);

        content.addView(overlay, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void confirmDelete(ProfileStore.Profile p) {
        panelDialog("Delete profile",
                "Delete \"" + p.name + "\"? Its sign-in data will be removed too.",
                null, "Delete", true, () -> {
                    List<ProfileStore.Profile> all = new ArrayList<>();
                    for (ProfileStore.Profile x : profiles) {
                        if (x.slot == p.slot) {
                            ProfileStore.Profile flagged = new ProfileStore.Profile(
                                    "deleting:" + x.id, x.name, x.slot, x.color, x.opened);
                            all.add(flagged);
                        } else {
                            all.add(x);
                        }
                    }
                    ProfileStore.save(this, all);
                    Intent i = new Intent(this, ProfileStore.activityClassForSlot(p.slot));
                    i.putExtra(BaseProfileActivity.EXTRA_PROFILE_ID, p.id);
                    i.putExtra(BaseProfileActivity.EXTRA_PROFILE_NAME, p.name);
                    i.putExtra(BaseProfileActivity.EXTRA_SLOT, p.slot);
                    i.putExtra(BaseProfileActivity.EXTRA_WIPE, true);
                    startActivity(i);
                    Toast.makeText(this, "Wiping profile data...", Toast.LENGTH_SHORT).show();
                }).show();
    }
}
