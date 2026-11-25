package id.riki.osvyn.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentUris;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.webkit.MimeTypeMap;
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
import java.nio.charset.StandardCharsets;
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

    // Desktop Hub palette. The theme-varying colors are fields, not
    // constants, since 1.5.0: applyTheme() reassigns them to the dark
    // set when the phone is in night mode, before any view is built.
    private int INK = 0xFF101014;
    private int INK2 = 0xFF3C3F47;
    private int INK3 = 0xFF83878F;
    private int GARIS = 0xFFE2E2E8;
    private int BG = 0xFFFBFBFC;
    private int PANEL = 0xFFFFFFFF;
    private static final int BLUE = 0xFF2B6FF2;
    private static final int BLUE_DARK = 0xFF1E56C8;
    private static final int CYAN = 0xFF15B3DA;
    private int GREEN = 0xFF1D9E57;
    private int GREEN_BG = 0xFFE5F6EC;
    private int GRAY_BG = 0xFFF1F1F4;
    private int YELLOW = 0xFF8A5C00;
    private int YELLOW_BG = 0xFFFDF3E0;
    private int RED = 0xFFB3372F;
    private int RED_BG = 0xFFFDECEA;
    private boolean darkTheme = false;

    /** Dark Hub theme (1.5.0): follows the phone's night mode. Only
     *  the app chrome changes; Muse pages keep the site's own look. */
    private void applyTheme() {
        int night = getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        darkTheme = night == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        if (!darkTheme) return;
        INK = 0xFFF4F4F6;
        INK2 = 0xFFC9CCD4;
        INK3 = 0xFF83878F;
        GARIS = 0xFF26262E;
        BG = 0xFF0E0E11;
        PANEL = 0xFF17171C;
        GREEN = 0xFF3ECF7E;
        GREEN_BG = 0xFF14301F;
        GRAY_BG = 0xFF1E1E24;
        YELLOW = 0xFFF2C14E;
        YELLOW_BG = 0xFF33270F;
        RED = 0xFFF08A84;
        RED_BG = 0xFF3A1D1B;
    }

    private LinearLayout listBox;
    private LinearLayout statsRow;
    private List<ProfileStore.Profile> profiles = new ArrayList<>();
    private boolean shiftShowing = false;
    // 1.5.0 Hub live state: settings controls + update banner + a 3 s
    // signature loop that re-renders only when something changed.
    private final Handler hubHandler = new Handler(Looper.getMainLooper());
    private String lastHubSignature = "";
    private TextView updateBanner;
    private TextView freezeDesc;
    private Button freezeBtn;
    private TextView quietDesc;
    private Button quietBtn;
    private Button quietSetBtn;
    private TextView ecoDesc;
    private Button ecoBtn;
    private TextView lockDesc;
    private Button lockBtn;
    private Button lockPinBtn;
    private TextView dlDesc;
    private Button dlFolderBtn;
    private static final int REQ_EXPORT = 8001;
    private static final int REQ_IMPORT = 8002;
    private final Runnable hubTick = new Runnable() {
        @Override public void run() {
            String sig = hubSignature();
            if (!sig.equals(lastHubSignature)) {
                lastHubSignature = sig;
                refresh();
            }
            hubHandler.postDelayed(this, 3000);
        }
    };

    private String hubSignature() {
        return CallMode.freshOwner(this) + "|" + HubState.totalUnread(this)
                + "|" + HubState.manualFreeze(this) + "|" + HubState.quietEnabled(this)
                + "|" + HubState.eco(this) + "|" + HubState.lockEnabled(this)
                + "|" + HubState.latestVersion(this)
                + "|" + HubState.dlFolder(this) + "|" + sleepSignature();
    }
    private String sleepSignature() {
        StringBuilder sb = new StringBuilder();
        for (int s = 1; s <= ProfileStore.MAX_PROFILES; s++) {
            sb.append(HubState.isSleeping(this, s) ? '1' : '0');
        }
        return sb.toString();
    }

    // Smoothness (1.4.1): profile rows animate in only on the first
    // Hub render. Replaying the staggered entrance on every onResume
    // made returning to the Hub feel slow; later refreshes are instant.
    private boolean hubAnimated = false;

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
        applyTheme();
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
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(
                darkTheme ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);

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
        TextView h1 = tv("Hub", 26, INK, Fonts.bold(this));
        h1.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(h1);
        Button add = new Button(this);
        add.setText("+ New profile");
        add.setTextSize(13.5f);
        add.setTypeface(Fonts.semibold(this));
        add.setTextColor(Color.WHITE);
        add.setBackground(Ui.press(BLUE, BLUE_DARK, dp(12), 0));
        add.setPadding(dp(18), dp(12), dp(18), dp(12));
        add.setAllCaps(false);
        add.setOnClickListener(v -> {
            Ui.haptic(v);
            v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90).setInterpolator(Ui.EASE)
                    .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).setDuration(160).setInterpolator(Ui.EASE).start()).start();
            showAddDialog();
        });
        head.addView(add);
        root.addView(head);

        TextView sub = tv("All your Muse profiles in one place. Open, put to sleep, or manage them without switching tabs one by one.", 13.5f, INK3, null);
        sub.setLineSpacing(0, 1.45f);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(4);
        sub.setLayoutParams(slp);
        root.addView(sub);

        // Stats strip with hairlines, like the Hub. Since 1.6.0 the
        // strip is four stat blocks (big number over a small label,
        // hairline dividers) instead of one run-on text line.
        statsRow = new LinearLayout(this);
        statsRow.setOrientation(LinearLayout.HORIZONTAL);
        statsRow.setPadding(0, dp(13), 0, dp(13));
        LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stlp.topMargin = dp(22);
        statsRow.setLayoutParams(stlp);
        View topLine = new View(this);
        topLine.setBackgroundColor(GARIS);
        topLine.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        LinearLayout statsBox = new LinearLayout(this);
        statsBox.setOrientation(LinearLayout.VERTICAL);
        statsBox.setLayoutParams(stlp);
        statsBox.addView(topLine);
        statsBox.addView(statsRow);
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

        TextView note = tv("Profiles you leave alone park their renderer in the background, so the watcher still hears new messages and reopening is instant. Sleep on a profile card rests it fully: its page closes and it stops listening until you open it again. The watcher runs while the app has not been force stopped.", 12.5f, INK3, null);
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

        // Update banner (1.5.0): hidden unless the repo carries a
        // newer version than the installed one.
        updateBanner = tv("", 12.5f, BLUE_DARK, Typeface.create("sans-serif", Typeface.BOLD));
        updateBanner.setBackground(Ui.press(GREEN_BG, 0xFFD3EDDD, dp(9), 0));
        updateBanner.setPadding(dp(12), dp(9), dp(12), dp(9));
        updateBanner.setVisibility(View.GONE);
        LinearLayout.LayoutParams ublp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ublp.topMargin = dp(16);
        updateBanner.setLayoutParams(ublp);
        updateBanner.setOnClickListener(v -> {
            Ui.haptic(v);
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://t.me/osvynofficialbot")));
            } catch (Exception ignored) {
            }
        });
        root.addView(updateBanner);

        // Settings section (1.5.0): every switch writes the shared
        // HubState the other processes read.
        TextView setSection = tv("SETTINGS", 11, INK3, Typeface.create("sans-serif", Typeface.BOLD));
        setSection.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams sslp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sslp.topMargin = dp(22);
        sslp.bottomMargin = dp(4);
        setSection.setLayoutParams(sslp);
        root.addView(setSection);

        // 1.6.0: the settings rows live in ONE grouped card with
        // hairline dividers, not six separate floating boxes.
        LinearLayout settingsCard = new LinearLayout(this);
        settingsCard.setOrientation(LinearLayout.VERTICAL);
        settingsCard.setBackground(round(PANEL, 16, GARIS));
        LinearLayout.LayoutParams sclp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sclp.topMargin = dp(8);
        settingsCard.setLayoutParams(sclp);
        root.addView(settingsCard);

        TextView[] d1 = new TextView[1];
        Button[] b1 = new Button[1];
        settingsCard.addView(settingRow("Freeze other profiles",
                "Freeze every other profile at once, as if a call was on. Their notifications pause until released.",
                d1, b1, "Freeze", v -> toggleFreeze(), null, null, null));
        freezeDesc = d1[0];
        freezeBtn = b1[0];

        TextView[] d2 = new TextView[1];
        Button[] b2 = new Button[1];
        Button[] b2b = new Button[1];
        settingsCard.addView(settingRow("Quiet hours",
                "Messages still arrive, but silently, inside the window.",
                d2, b2, "Off", v -> toggleQuiet(), b2b, "Set", v -> showQuietDialog()));
        quietDesc = d2[0];
        quietBtn = b2[0];
        quietSetBtn = b2b[0];

        TextView[] d3 = new TextView[1];
        Button[] b3 = new Button[1];
        settingsCard.addView(settingRow("Eco mode",
                "Stretches background message scans from 15 s to 60 s. Easier on the battery.",
                d3, b3, "Off", v -> toggleEco(), null, null, null));
        ecoDesc = d3[0];
        ecoBtn = b3[0];

        TextView[] d4 = new TextView[1];
        Button[] b4 = new Button[1];
        Button[] b4b = new Button[1];
        settingsCard.addView(settingRow("App lock",
                "A PIN, or your fingerprint, is required to open Osvyn.",
                d4, b4, "Off", v -> toggleLock(), b4b, "PIN", v -> showPinDialog()));
        lockDesc = d4[0];
        lockBtn = b4[0];
        lockPinBtn = b4b[0];

        TextView[] d5 = new TextView[1];
        Button[] b5 = new Button[1];
        Button[] b5b = new Button[1];
        settingsCard.addView(settingRow("Downloads",
                "Files downloaded through the app, reopenable from here.",
                d5, b5, "View", v -> showDownloads(), b5b, "Folder", v -> showDlFolderDialog()));
        dlDesc = d5[0];
        dlFolderBtn = b5b[0];

        TextView[] d6 = new TextView[1];
        Button[] b6 = new Button[1];
        Button[] b6b = new Button[1];
        settingsCard.addView(settingRow("Profiles backup",
                "Export the profile list to a file, or import it on another phone. Sign-ins never travel; each profile signs in again there.",
                d6, b6, "Export", v -> startExport(), b6b, "Import", v -> startImport()));

        for (int di = settingsCard.getChildCount() - 1; di >= 1; di--) {
            View div = new View(this);
            div.setBackgroundColor(GARIS);
            LinearLayout.LayoutParams dvlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
            dvlp.leftMargin = dp(16);
            div.setLayoutParams(dvlp);
            settingsCard.addView(div, di);
        }

        TextView foot = tv("Osvyn Mobile 1.7.0 - max " + ProfileStore.MAX_PROFILES + " profiles", 11, INK3, null);
        foot.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.topMargin = dp(18);
        foot.setLayoutParams(flp);
        root.addView(foot);

        // Hub entrance animation (hubMasuk)
        if (Ui.motionOk(this)) {
            root.setAlpha(0f);
            root.setTranslationY(dp(8));
            root.animate().alpha(1f).translationY(0f)
                    .setDuration(220)
                    .setInterpolator(Ui.EASE)
                    .start();
        }

        setContentView(scroll);

        if (getIntent() != null && getIntent().getBooleanExtra("open_add", false)) {
            getIntent().removeExtra("open_add");
            root.post(this::showAddDialog);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // App lock gate (1.7.0): the Splash gate alone was bypassable
        // by re-entering from recents, so the Hub asks for the PIN
        // itself after a long backgrounding. While the gate is up the
        // foreground fact is deliberately not stamped (see LockGate).
        if (!LockGate.enforceIfNeeded(this)) {
            HubState.noteLockForeground(this);
        }
        finishPendingWipes();
        refresh();
        maybeCheckUpdate();
        lastHubSignature = hubSignature();
        hubHandler.removeCallbacks(hubTick);
        hubHandler.postDelayed(hubTick, 3000);
    }

    @Override
    protected void onPause() {
        hubHandler.removeCallbacks(hubTick);
        HubState.noteLockBackground(this);
        super.onPause();
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

    /** One stat block in the Hub stats strip (1.6.0). */
    private void addStat(LinearLayout parent, String value, String label,
                         boolean dividerBefore, boolean accent) {
        if (dividerBefore) {
            View line = new View(this);
            line.setBackgroundColor(GARIS);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(dp(1), dp(34));
            llp.rightMargin = dp(14);
            line.setLayoutParams(llp);
            parent.addView(line);
        }
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        block.addView(tv(value, 20, accent ? BLUE : INK, Fonts.semibold(this)));
        TextView l = tv(label, 10.5f, INK3, Typeface.create("sans-serif", Typeface.BOLD));
        l.setLetterSpacing(0.06f);
        LinearLayout.LayoutParams llp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp2.topMargin = dp(2);
        l.setLayoutParams(llp2);
        block.addView(l);
        parent.addView(block);
    }

    private void refresh() {
        profiles = ProfileStore.load(this);
        int opened = 0;
        for (ProfileStore.Profile p : profiles) if (p.opened > 0) opened++;
        int unreadTotal = HubState.totalUnread(this);
        statsRow.removeAllViews();
        addStat(statsRow, String.valueOf(profiles.size()), "PROFILES", false, false);
        addStat(statsRow, String.valueOf(opened), "OPENED", true, false);
        addStat(statsRow, String.valueOf(profiles.size() - opened), "NOT SIGNED", true, false);
        addStat(statsRow, String.valueOf(unreadTotal), "UNREAD", true, unreadTotal > 0);
        refreshSettings();

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
            Button first = new Button(this);
            first.setText("Create your first profile");
            first.setTextSize(14);
            first.setTypeface(Fonts.semibold(this));
            first.setTextColor(Color.WHITE);
            first.setAllCaps(false);
            first.setBackground(Ui.press(BLUE, BLUE_DARK, dp(12), 0));
            first.setPadding(dp(20), dp(12), dp(20), dp(12));
            LinearLayout.LayoutParams flp2 = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            flp2.topMargin = dp(16);
            first.setLayoutParams(flp2);
            first.setOnClickListener(v -> {
                Ui.haptic(v);
                showAddDialog();
            });
            box.addView(first);
            listBox.addView(box);
            return;
        }
        for (int i = 0; i < profiles.size(); i++) {
            View row = profileRow(profiles.get(i));
            if (!hubAnimated && Ui.motionOk(this)) {
                row.setAlpha(0f);
                row.setTranslationY(dp(6));
                row.animate().alpha(1f).translationY(0f).setDuration(180)
                        .setInterpolator(Ui.EASE)
                        .setStartDelay(Math.min(i * 35L, 140L)).start();
            }
            listBox.addView(row);
        }
        hubAnimated = true;
    }

    private View profileRow(ProfileStore.Profile p) {
        boolean deleting = p.id.startsWith("deleting:");
        boolean opened = p.opened > 0;
        boolean inCall = p.id.equals(CallMode.freshOwner(this));
        int unread = HubState.unread(this, p.slot);

        // 1.6.0 card: identity block on top, hairline, text actions in
        // a footer row. The old stacked Open/Delete pill column read
        // like a stock admin table; the unread count now rides the
        // avatar as a badge instead of a second chip row.
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(round(PANEL, 16, GARIS));
        card.setPadding(dp(16), dp(14), dp(16), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        card.setLayoutParams(lp);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(top);

        FrameLayout avatarBox = new FrameLayout(this);
        avatarBox.setLayoutParams(new LinearLayout.LayoutParams(dp(46), dp(46)));
        TextView avatar = new TextView(this);
        avatar.setText(p.name.trim().isEmpty() ? "?" : p.name.trim().substring(0, 1).toUpperCase());
        avatar.setTextColor(Color.WHITE);
        avatar.setTextSize(16);
        avatar.setTypeface(Fonts.semibold(this));
        avatar.setGravity(Gravity.CENTER);
        GradientDrawable ac = new GradientDrawable();
        ac.setShape(GradientDrawable.OVAL);
        ac.setColor(ProfileStore.AURA[(p.slot - 1) % ProfileStore.AURA.length]);
        avatar.setBackground(ac);
        avatarBox.addView(avatar, new FrameLayout.LayoutParams(dp(46), dp(46)));
        View pdot = new View(this);
        GradientDrawable pd = new GradientDrawable();
        pd.setShape(GradientDrawable.OVAL);
        pd.setColor(opened ? GREEN : 0xFFC9CCD4);
        pd.setStroke(dp(2), PANEL);
        pdot.setBackground(pd);
        FrameLayout.LayoutParams pdlp = new FrameLayout.LayoutParams(dp(12), dp(12));
        pdlp.gravity = Gravity.BOTTOM | Gravity.RIGHT;
        avatarBox.addView(pdot, pdlp);
        if (unread > 0) {
            TextView badge = new TextView(this);
            badge.setText(String.valueOf(unread));
            badge.setTextColor(Color.WHITE);
            badge.setTextSize(10.5f);
            badge.setTypeface(Fonts.bold(this));
            badge.setGravity(Gravity.CENTER);
            GradientDrawable bd = new GradientDrawable();
            bd.setShape(GradientDrawable.OVAL);
            bd.setColor(BLUE);
            bd.setStroke(dp(2), PANEL);
            badge.setBackground(bd);
            FrameLayout.LayoutParams blp2 = new FrameLayout.LayoutParams(dp(19), dp(19));
            blp2.gravity = Gravity.TOP | Gravity.RIGHT;
            avatarBox.addView(badge, blp2);
        }
        top.addView(avatarBox);

        LinearLayout ident = new LinearLayout(this);
        ident.setOrientation(LinearLayout.VERTICAL);
        ident.setPadding(dp(13), 0, dp(4), 0);
        ident.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView name = tv(p.name, 15, INK, Fonts.semibold(this));
        name.setMaxLines(1);
        name.setEllipsize(TextUtils.TruncateAt.END);
        ident.addView(name);
        String meta = deleting ? "Deleting... reopen it once to finish the wipe"
                : inCall ? "In a call now"
                : HubState.isSleeping(this, p.slot) ? "Asleep \u00b7 resting until reopened"
                : opened ? "Asleep \u00b7 last opened " + relative(p.opened)
                : "Not signed in yet";
        TextView metaTv = tv(meta, 12.5f, inCall ? GREEN : INK3,
                inCall ? Fonts.semibold(this) : null);
        metaTv.setMaxLines(1);
        metaTv.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = dp(3);
        metaTv.setLayoutParams(mlp);
        ident.addView(metaTv);
        top.addView(ident);

        View line = new View(this);
        line.setBackgroundColor(GARIS);
        LinearLayout.LayoutParams linelp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        linelp.topMargin = dp(12);
        line.setLayoutParams(linelp);
        card.addView(line);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        TextView open = tv("Open", 13.5f, BLUE_DARK, Fonts.semibold(this));
        open.setBackground(Ui.press(Color.TRANSPARENT, darkTheme ? 0xFF22222A : 0xFFF1F1F4, dp(8), 0));
        open.setPadding(dp(10), dp(9), dp(14), dp(9));
        open.setOnClickListener(v -> {
            Ui.haptic(v);
            openProfile(p);
        });
        actions.addView(open);
        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        actions.addView(spacer);
        if (!deleting) {
            TextView rename = tv("Rename", 13.5f, INK2, Fonts.semibold(this));
            rename.setBackground(Ui.press(Color.TRANSPARENT, darkTheme ? 0xFF22222A : 0xFFF1F1F4, dp(8), 0));
            rename.setPadding(dp(10), dp(9), dp(10), dp(9));
            rename.setOnClickListener(v -> {
                Ui.haptic(v);
                showRenameDialog(p);
            });
            actions.addView(rename);
        }
        if (!deleting && opened && !inCall && !HubState.isSleeping(this, p.slot)) {
            TextView sleep = tv("Sleep", 13.5f, INK2, Fonts.semibold(this));
            sleep.setBackground(Ui.press(Color.TRANSPARENT, darkTheme ? 0xFF22222A : 0xFFF1F1F4, dp(8), 0));
            sleep.setPadding(dp(10), dp(9), dp(10), dp(9));
            sleep.setOnClickListener(v -> {
                Ui.haptic(v);
                HubState.requestSleep(this, p.slot);
                Toast.makeText(this, p.name + " is going to sleep", Toast.LENGTH_SHORT).show();
                refresh();
            });
            actions.addView(sleep);
        }
        TextView del = tv("Delete", 13.5f, RED, Fonts.semibold(this));
        del.setBackground(Ui.press(Color.TRANSPARENT, RED_BG, dp(8), 0));
        del.setPadding(dp(14), dp(9), dp(10), dp(9));
        del.setOnClickListener(v -> {
            Ui.haptic(v);
            confirmDelete(p);
        });
        actions.addView(del);
        card.addView(actions);

        // The whole card opens the profile, and it answers the touch
        // with a small press scale so it never feels dead.
        card.setOnTouchListener((v, ev) -> {
            if (ev.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                v.animate().scaleX(0.985f).scaleY(0.985f).setDuration(90).setInterpolator(Ui.EASE).start();
            } else if (ev.getAction() == android.view.MotionEvent.ACTION_UP
                    || ev.getAction() == android.view.MotionEvent.ACTION_CANCEL) {
                v.animate().scaleX(1f).scaleY(1f).setDuration(140).setInterpolator(Ui.EASE).start();
            }
            return false;
        });
        card.setOnClickListener(v -> {
            Ui.haptic(v);
            openProfile(p);
        });
        return card;
    }

    private Button smallButton(String text, int bg, int fg, int stroke) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(13);
        b.setTypeface(Fonts.semibold(this));
        b.setTextColor(fg);
        int pressedBg = bg == BLUE ? BLUE_DARK : bg == RED_BG ? 0xFFF9DAD6 : 0xFFEDEDF1;
        b.setBackground(Ui.press(bg, pressedBg, dp(9), stroke));
        b.setPadding(dp(14), dp(10), dp(14), dp(10));
        b.setAllCaps(false);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        return b;
    }

    private void openProfile(ProfileStore.Profile p) {
        // Opening a profile wakes it: any manual Sleep state clears.
        HubState.clearSleep(this, p.slot);
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
            input.setOnFocusChangeListener((v, hasFocus) ->
                    input.setBackground(round(PANEL, 9, hasFocus ? BLUE : GARIS)));
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

        dlg.setOnShowListener(d -> {
            if (Ui.motionOk(this)) {
                card.setAlpha(0f);
                card.setScaleX(0.96f);
                card.setScaleY(0.96f);
                card.setTranslationY(dp(8));
                card.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                        .setDuration(220).setInterpolator(Ui.EASE).start();
            }
        });
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
        TextView word = tv("Osvyn", 44, 0xFF101014, Fonts.bold(this));
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
        track.addView(fill, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        wrap.addView(track);

        wrap.setAlpha(0f);
        wrap.setScaleX(0.96f);
        wrap.setScaleY(0.96f);
        wrap.setTranslationY(dp(8));
        wrap.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                .setStartDelay(60).setDuration(320)
                .setInterpolator(Ui.EASE).start();

        fill.setPivotX(0f);
        if (Ui.motionOk(this)) {
            fill.setScaleX(0f);
            fill.animate().scaleX(0.82f).setStartDelay(180).setDuration(900)
                    .setInterpolator(Ui.EASE).start();
        } else {
            fill.setScaleX(0.82f);
        }

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
            overlay.animate().alpha(0f).setDuration(200).setInterpolator(Ui.EASE).withEndAction(() -> {
                content.removeView(overlay);
                openProfile(p);
            }).start();
        };
        overlay.setOnClickListener(v -> finishShift.run());
        h.postDelayed(finishShift, 1400);

        content.addView(overlay, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    // ---------- Settings (1.5.0) ----------

    private LinearLayout settingRow(String title, String descText, TextView[] descHolder,
                                    Button[] btnHolder, String btnText, View.OnClickListener onBtn,
                                    Button[] btn2Holder, String btn2Text, View.OnClickListener onBtn2) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        row.setLayoutParams(lp);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        texts.addView(tv(title, 14, INK, Typeface.create("sans-serif", Typeface.BOLD)));
        TextView desc = tv(descText, 12, INK3, null);
        desc.setLineSpacing(0, 1.3f);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(2);
        desc.setLayoutParams(dlp);
        texts.addView(desc);
        if (descHolder != null) descHolder[0] = desc;
        row.addView(texts);

        Button b = smallButton(btnText, GRAY_BG, INK2, GARIS);
        b.setOnClickListener(v -> {
            Ui.haptic(v);
            onBtn.onClick(v);
        });
        if (btnHolder != null) btnHolder[0] = b;
        row.addView(b);

        if (btn2Holder != null && onBtn2 != null) {
            Button b2 = smallButton(btn2Text, GRAY_BG, INK2, GARIS);
            LinearLayout.LayoutParams b2lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            b2lp.leftMargin = dp(7);
            b2.setLayoutParams(b2lp);
            b2.setOnClickListener(v -> {
                Ui.haptic(v);
                onBtn2.onClick(v);
            });
            btn2Holder[0] = b2;
            row.addView(b2);
        }
        return row;
    }

    private void styleToggle(Button b, boolean on, boolean danger) {
        if (b == null) return;
        if (!on) {
            b.setTextColor(INK2);
            b.setBackground(Ui.press(GRAY_BG, 0xFFEDEDF1, dp(9), GARIS));
        } else if (danger) {
            b.setTextColor(RED);
            b.setBackground(Ui.press(RED_BG, darkTheme ? 0xFF4A2422 : 0xFFF9DAD6, dp(9),
                    darkTheme ? 0xFF6E3532 : 0xFFEFC4C0));
        } else {
            b.setTextColor(BLUE);
            b.setBackground(Ui.press(darkTheme ? 0xFF182848 : 0xFFEAF1FE,
                    darkTheme ? 0xFF1E3A66 : 0xFFD7E5FD, dp(9),
                    darkTheme ? 0xFF2A4A7F : 0xFFB8CFFA));
        }
    }

    private void refreshSettings() {
        if (freezeBtn != null) {
            boolean on = HubState.manualFreeze(this);
            freezeBtn.setText(on ? "Release" : "Freeze");
            styleToggle(freezeBtn, on, true);
            freezeDesc.setText(on
                    ? "Other profiles are frozen right now. Tap Release to wake them."
                    : "Freeze every other profile at once, as if a call was on. Their notifications pause until released.");
        }
        if (quietBtn != null) {
            boolean on = HubState.quietEnabled(this);
            quietBtn.setText(on ? "On" : "Off");
            styleToggle(quietBtn, on, false);
            quietDesc.setText(on
                    ? "On, " + HubState.fmtMin(HubState.quietStartMin(this)) + " to "
                            + HubState.fmtMin(HubState.quietEndMin(this))
                            + ". Messages still arrive, but silently, inside the window."
                    : "Off. Messages ring at all hours.");
        }
        if (ecoBtn != null) {
            boolean on = HubState.eco(this);
            ecoBtn.setText(on ? "On" : "Off");
            styleToggle(ecoBtn, on, false);
            ecoDesc.setText(on
                    ? "On. Background scans run every 60 s; a notification can lag a little."
                    : "Off. Background scans run every 15 s.");
        }
        if (lockBtn != null) {
            boolean has = HubState.hasPin(this);
            boolean on = HubState.lockEnabled(this);
            lockBtn.setText(on ? "On" : "Off");
            styleToggle(lockBtn, on, false);
            lockPinBtn.setText(has ? "PIN" : "Set PIN");
            lockDesc.setText(!has
                    ? "No PIN set yet. Set one to protect every signed-in account in here."
                    : on ? "On. A PIN, or your fingerprint, is required to open Osvyn."
                    : "A PIN is set, but the lock is off.");
        }
        if (dlDesc != null) {
            dlDesc.setText("Files downloaded through the app land in "
                    + HubState.dlFolderLabel(this) + ", reopenable from here.");
        }
        if (updateBanner != null) {
            String latest = HubState.latestVersion(this);
            boolean newer = !latest.isEmpty()
                    && HubState.compareVersions(latest, installedVersion()) > 0;
            updateBanner.setVisibility(newer ? View.VISIBLE : View.GONE);
            if (newer) {
                updateBanner.setText("Update available: Osvyn Mobile " + latest
                        + " - tap to get it in the bot");
            }
        }
    }

    private String installedVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "0";
        }
    }

    private void toggleFreeze() {
        boolean next = !HubState.manualFreeze(this);
        HubState.setBool(this, "freeze.manual", next);
        CallMode.setActive(this, "hub-manual", next);
        refresh();
    }

    private void toggleQuiet() {
        HubState.setBool(this, "quiet.enabled", !HubState.quietEnabled(this));
        refresh();
    }

    private void showQuietDialog() {
        EditText input = new EditText(this);
        input.setText(HubState.fmtMin(HubState.quietStartMin(this)) + "-"
                + HubState.fmtMin(HubState.quietEndMin(this)));
        input.setHint("22:00-07:00");
        panelDialog("Quiet hours", "Write the window as start-end, for example 22:00-07:00.",
                input, "Save", false, () -> {
                    String[] parts = input.getText().toString().trim().split("-");
                    Integer start = parts.length == 2 ? parseHm(parts[0]) : null;
                    Integer end = parts.length == 2 ? parseHm(parts[1]) : null;
                    if (start == null || end == null) {
                        Toast.makeText(this, "Time format not recognized", Toast.LENGTH_LONG).show();
                        return;
                    }
                    HubState.setQuietWindow(this, start, end);
                    HubState.setBool(this, "quiet.enabled", true);
                    refresh();
                }).show();
    }

    private Integer parseHm(String s) {
        try {
            String[] hm = s.trim().split(":");
            int h = Integer.parseInt(hm[0].trim());
            int m = hm.length > 1 ? Integer.parseInt(hm[1].trim()) : 0;
            if (h < 0 || h > 23 || m < 0 || m > 59) return null;
            return h * 60 + m;
        } catch (Exception e) {
            return null;
        }
    }

    private void toggleEco() {
        HubState.setBool(this, "eco.enabled", !HubState.eco(this));
        refresh();
    }

    private void toggleLock() {
        if (!HubState.hasPin(this)) {
            Toast.makeText(this, "Set a PIN first", Toast.LENGTH_LONG).show();
            showPinDialog();
            return;
        }
        HubState.setBool(this, "lock.enabled", !HubState.lockEnabled(this));
        refresh();
    }

    private void showPinDialog() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setHint("4 to 6 digits");
        panelDialog("App lock PIN", "This PIN opens Osvyn. Your fingerprint works too, when the phone has one enrolled.",
                input, "Save", false, () -> {
                    String pin = input.getText().toString().trim();
                    if (!pin.matches("\\d{4,6}")) {
                        Toast.makeText(this, "PIN must be 4 to 6 digits", Toast.LENGTH_LONG).show();
                        return;
                    }
                    HubState.setPin(this, pin);
                    Toast.makeText(this, "PIN saved, lock on", Toast.LENGTH_SHORT).show();
                    refresh();
                }).show();
    }

    // ---------- Rename profile (1.7.0) ----------

    private void showRenameDialog(ProfileStore.Profile p) {
        EditText input = new EditText(this);
        input.setText(p.name);
        input.setSelection(p.name.length());
        input.setHint("Profile name");
        panelDialog("Rename profile", "The new name shows in the Hub and in the profile's tab bar the next time it opens.",
                input, "Save", false, () -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(this, "Name cannot be empty", Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (name.length() > 30) name = name.substring(0, 30);
                    List<ProfileStore.Profile> all = new ArrayList<>();
                    for (ProfileStore.Profile x : profiles) {
                        if (x.slot == p.slot) {
                            all.add(new ProfileStore.Profile(
                                    x.id, name, x.slot, x.color, x.opened));
                        } else {
                            all.add(x);
                        }
                    }
                    ProfileStore.save(this, all);
                    Toast.makeText(this, "Renamed to " + name, Toast.LENGTH_SHORT).show();
                    refresh();
                }).show();
    }

    // ---------- Download folder (1.7.0) ----------

    private void showDlFolderDialog() {
        android.app.Dialog dlg = new android.app.Dialog(this);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(round(PANEL, 16, 0));
        card.setPadding(dp(24), dp(22), dp(24), dp(20));
        card.addView(tv("Download folder", 18, INK, Fonts.bold(this)));
        TextView sub = tv("New downloads land in the folder you pick here. Files already downloaded stay where they are.", 12.5f, INK3, null);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(6);
        sub.setLayoutParams(slp);
        card.addView(sub);

        String current = HubState.dlFolder(this);
        for (int i = 0; i < HubState.DL_FOLDER_VALUES.length; i++) {
            final String value = HubState.DL_FOLDER_VALUES[i];
            boolean active = value.equals(current);
            TextView row = tv((active ? "●  " : "○  ") + HubState.DL_FOLDER_LABELS[i],
                    14.5f, active ? BLUE_DARK : INK, Fonts.semibold(this));
            row.setPadding(dp(6), dp(13), dp(6), dp(13));
            row.setBackground(Ui.press(Color.TRANSPARENT, darkTheme ? 0xFF22222A : 0xFFF1F1F4, dp(8), 0));
            row.setOnClickListener(v -> {
                HubState.setDlFolder(this, value);
                dlg.dismiss();
                Toast.makeText(this, "Downloads now land in " + HubState.dlFolderLabel(this),
                        Toast.LENGTH_SHORT).show();
                refresh();
            });
            card.addView(row);
            View dv = new View(this);
            dv.setBackgroundColor(GARIS);
            card.addView(dv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        }

        Button close = smallButton("Close", PANEL, INK2, GARIS);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(16);
        clp.gravity = Gravity.RIGHT;
        close.setLayoutParams(clp);
        close.setOnClickListener(v -> dlg.dismiss());
        card.addView(close);

        dlg.setContentView(card);
        if (dlg.getWindow() != null) {
            dlg.getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            int w = (int) (getResources().getDisplayMetrics().widthPixels * 0.88f);
            dlg.getWindow().setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dlg.show();
    }

    // ---------- Downloads history (1.5.0) ----------

    private void showDownloads() {
        org.json.JSONArray arr = HubState.loadDownloads(this);
        android.app.Dialog dlg = new android.app.Dialog(this);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(round(PANEL, 16, 0));
        card.setPadding(dp(24), dp(22), dp(24), dp(20));
        card.addView(tv("Downloads", 18, INK, Fonts.bold(this)));

        if (arr.length() == 0) {
            TextView m = tv("Nothing has been downloaded through the app yet.", 13.5f, INK2, null);
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            mlp.topMargin = dp(8);
            m.setLayoutParams(mlp);
            card.addView(m);
        } else {
            ScrollView sv = new ScrollView(this);
            LinearLayout list = new LinearLayout(this);
            list.setOrientation(LinearLayout.VERTICAL);
            sv.addView(list);
            LinearLayout.LayoutParams svlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(320));
            svlp.topMargin = dp(10);
            sv.setLayoutParams(svlp);
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String name = o.optString("name", "file");
                long size = o.optLong("size", 0);
                long at = o.optLong("at", 0);
                String prof = o.optString("profile", "");
                LinearLayout r = new LinearLayout(this);
                r.setOrientation(LinearLayout.VERTICAL);
                r.setPadding(0, dp(8), 0, dp(8));
                TextView n = tv(name, 13.5f, INK, Typeface.create("sans-serif", Typeface.BOLD));
                n.setMaxLines(1);
                r.addView(n);
                String meta = (size >= 1024 ? (size / 1024) + " KB" : size + " B")
                        + (at > 0 ? " - " + relative(at) : "")
                        + (prof.isEmpty() ? "" : " - " + prof);
                r.addView(tv(meta, 11.5f, INK3, null));
                r.setOnClickListener(v -> {
                    dlg.dismiss();
                    openDownload(name);
                });
                list.addView(r);
                View dv = new View(this);
                dv.setBackgroundColor(GARIS);
                list.addView(dv, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
            }
            list.post(() -> {
                int h = list.getHeight();
                if (h > 0 && h < dp(320)) {
                    svlp.height = h;
                    sv.setLayoutParams(svlp);
                }
            });
            card.addView(sv);
        }

        Button close = smallButton("Close", PANEL, INK2, GARIS);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(16);
        clp.gravity = Gravity.RIGHT;
        close.setLayoutParams(clp);
        close.setOnClickListener(v -> dlg.dismiss());
        card.addView(close);

        dlg.setOnShowListener(d -> {
            if (Ui.motionOk(this)) {
                card.setAlpha(0f);
                card.setScaleX(0.96f);
                card.setScaleY(0.96f);
                card.setTranslationY(dp(8));
                card.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                        .setDuration(220).setInterpolator(Ui.EASE).start();
            }
        });
        dlg.setContentView(card);
        if (dlg.getWindow() != null) {
            dlg.getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            int w = (int) (getResources().getDisplayMetrics().widthPixels * 0.88f);
            dlg.getWindow().setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dlg.show();
    }

    private void openDownload(String name) {
        try {
            Uri uri = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try (Cursor c = getContentResolver().query(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        new String[]{MediaStore.Downloads._ID},
                        MediaStore.Downloads.DISPLAY_NAME + "=?",
                        new String[]{name}, null)) {
                    if (c != null && c.moveToFirst()) {
                        uri = ContentUris.withAppendedId(
                                MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0));
                    }
                }
            }
            if (uri == null) {
                File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                File f = dir == null ? null : new File(dir, name);
                if (f != null && f.exists()) uri = Uri.fromFile(f);
            }
            if (uri == null) {
                Toast.makeText(this, "File not found in Downloads", Toast.LENGTH_LONG).show();
                return;
            }
            String ext = "";
            int dot = name.lastIndexOf('.');
            if (dot >= 0) ext = name.substring(dot + 1).toLowerCase();
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, mime == null ? "*/*" : mime);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Could not open the file", Toast.LENGTH_LONG).show();
        }
    }

    // ---------- Profiles backup (1.5.0) ----------

    private void startExport() {
        try {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/json");
            i.putExtra(Intent.EXTRA_TITLE, "osvyn-profiles.json");
            startActivityForResult(i, REQ_EXPORT);
        } catch (Exception e) {
            Toast.makeText(this, "Export is not available on this phone", Toast.LENGTH_LONG).show();
        }
    }

    private void startImport() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/json");
            startActivityForResult(i, REQ_IMPORT);
        } catch (Exception e) {
            Toast.makeText(this, "Import is not available on this phone", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQ_EXPORT) {
            try {
                org.json.JSONArray arr = new org.json.JSONArray();
                for (ProfileStore.Profile p : profiles) {
                    org.json.JSONObject o = new org.json.JSONObject();
                    o.put("name", p.name);
                    o.put("color", p.color);
                    arr.put(o);
                }
                try (java.io.OutputStream out = getContentResolver().openOutputStream(uri)) {
                    out.write(arr.toString(2).getBytes(StandardCharsets.UTF_8));
                }
                Toast.makeText(this, "Exported " + profiles.size() + " profiles", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(this, "Export failed", Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == REQ_IMPORT) {
            try {
                String text;
                try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                    byte[] chunk = new byte[4096];
                    int n;
                    while ((n = in.read(chunk)) != -1) bos.write(chunk, 0, n);
                    text = new String(bos.toByteArray(), StandardCharsets.UTF_8);
                }
                org.json.JSONArray arr = new org.json.JSONArray(text);
                List<ProfileStore.Profile> all = ProfileStore.load(this);
                java.util.Set<String> names = new java.util.HashSet<>();
                for (ProfileStore.Profile p : all) names.add(p.name);
                int added = 0;
                for (int i = 0; i < arr.length(); i++) {
                    if (all.size() >= ProfileStore.MAX_PROFILES) break;
                    org.json.JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    String name = o.optString("name", "").trim();
                    if (name.isEmpty() || names.contains(name)) continue;
                    int slot = ProfileStore.freeSlot(all);
                    if (slot < 0) break;
                    all.add(new ProfileStore.Profile(
                            "profil-" + System.currentTimeMillis() + "-" + slot,
                            name, slot, o.optInt("color", slot - 1)));
                    names.add(name);
                    added++;
                }
                if (added > 0) ProfileStore.save(this, all);
                refresh();
                Toast.makeText(this, added > 0
                        ? "Imported " + added + " profiles. Sign in inside each one."
                        : "Nothing new to import", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(this, "Import failed: not a profiles file", Toast.LENGTH_LONG).show();
            }
        }
    }

    // ---------- Update check (1.5.0) ----------

    /** At most once per 6 hours, read the public repo README (it
     *  carries the current version) and remember the newest version
     *  seen. The banner in refreshSettings() does the comparing. */
    private void maybeCheckUpdate() {
        if (System.currentTimeMillis() - HubState.updateCheckedAt(this) < 6 * 3600_000L) return;
        new Thread(() -> {
            try {
                java.net.URL u = new java.net.URL(
                        "https://raw.githubusercontent.com/Whydiriki700/osvyn-mobile/main/README.md");
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) u.openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                StringBuilder sb = new StringBuilder();
                try (java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null && sb.length() < 20000) {
                        sb.append(line).append('\n');
                    }
                }
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("Version (\\d+\\.\\d+\\.\\d+)").matcher(sb.toString());
                if (m.find()) {
                    HubState.setLatestVersion(this, m.group(1), System.currentTimeMillis());
                    runOnUiThread(this::refreshSettings);
                }
            } catch (Exception ignored) {
            }
        }).start();
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
