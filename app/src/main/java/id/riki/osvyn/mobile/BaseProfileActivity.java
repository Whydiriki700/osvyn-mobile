package id.riki.osvyn.mobile;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One Muse profile: a WebView loading muse.ai inside a dedicated process.
 *
 * Isolation: WebView.setDataDirectorySuffix() is called before any WebView
 * exists in this process, and each slot owns its process (:profileN), so the
 * suffix can never clash. Cookies and storage are therefore fully separate
 * per profile, exactly like the desktop build's per-profile data folders.
 *
 * DeepSleep: after 3 minutes in the background the page is released to
 * about:blank (RAM freed, login cookies kept on disk). Coming back reloads
 * muse.ai with the session intact.
 */
public abstract class BaseProfileActivity extends Activity {

    public static final String EXTRA_PROFILE_ID = "profile_id";
    public static final String EXTRA_PROFILE_NAME = "profile_name";
    public static final String EXTRA_SLOT = "slot";
    public static final String EXTRA_WIPE = "wipe";

    private static final long DEEP_SLEEP_MS = 180_000L;
    private static final int FILE_CHOOSER_REQUEST = 1001;

    protected abstract int mySlot();

    private WebView webView;
    private FrameLayout stage;
    private WebView popupView;
    private View popupBox;
    private ProgressBar progress;
    private boolean slept = false;
    private ValueCallback<Uri[]> fileCallback;
    private volatile boolean inForeground = false;
    private String myProfileId = "";
    private String myProfileName = "";
    private int mySlotValue = 0;
    private long lastNotifiedAt = 0L;
    private String lastNotifiedSig = "";

    /** Desktop Chrome UA: muse.ai serves Osvyn desktop a full layout;
     *  its mobile layout is what left artifact panels blank in testing. */
    static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    private final Handler handler = new Handler(Looper.getMainLooper());
    // DeepSleep since 1.3.0: the page stays loaded and its JavaScript
    // keeps running (only the renderer is waived in onPause) instead of
    // being blanked, so the message watcher keeps hearing new messages
    // while the profile is in the background, and reopening is instant.
    // The old about:blank unload would have made notifications
    // impossible. The runnable now only marks the profile as slept;
    // the Hub card text describes this state.
    private final Runnable sleepRunnable = () -> slept = true;

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        int slot = getIntent() != null ? getIntent().getIntExtra(EXTRA_SLOT, mySlot()) : mySlot();

        // Must run before any WebView is created in this process.
        try {
            WebView.setDataDirectorySuffix("osvyn_profile_" + slot);
        } catch (IllegalStateException ignored) {
            // Process already initialised with this slot's suffix. Same slot,
            // same process, so isolation still holds.
        }

        super.onCreate(savedInstanceState);

        if (getIntent() != null && getIntent().getBooleanExtra(EXTRA_WIPE, false)) {
            wipeAndFinish(slot);
            return;
        }

        String profileName = getIntent() != null
                ? getIntent().getStringExtra(EXTRA_PROFILE_NAME) : null;
        if (profileName == null) profileName = "Profile " + slot;
        String profileId = getIntent() != null
                ? getIntent().getStringExtra(EXTRA_PROFILE_ID) : null;
        myProfileId = profileId == null ? "" : profileId;
        myProfileName = profileName;
        mySlotValue = slot;

        buildUi(profileName, slot);
        webView.loadUrl(ProfileStore.MUSE_URL);
    }

    private void wipeAndFinish(int slot) {
        try {
            WebStorage.getInstance().deleteAllData();
            CookieManager cm = CookieManager.getInstance();
            cm.removeAllCookies(null);
            cm.flush();
            WebView dummy = null;
            // No WebView was created, so storage wipe above covers the profile.
        } catch (Exception ignored) {
        }
        try (FileOutputStream out = new FileOutputStream(ProfileStore.wipeMarker(this, slot))) {
            out.write("done".getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
        Toast.makeText(this, "Profile data wiped", Toast.LENGTH_SHORT).show();
        finish();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void buildUi(String profileName, int slot) {
        getWindow().setStatusBarColor(0xFF101014);
        getWindow().setNavigationBarColor(0xFF101014);
        getWindow().getDecorView().setSystemUiVisibility(0);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF101014);

        // The desktop profile bar (#bar in index.html), ported to mobile:
        // one dark row of pill tabs on #101014. Inactive tabs #1B1B21 with a
        // #26262E border and #B9B6C4 text; the active tab inverts to #E8E6DF
        // with #141419 text. Dots are cyan when awake, #8A8798 when asleep.
        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setBackgroundColor(0xFF101014);
        scroller.setHorizontalScrollBarEnabled(false);
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(8), dp(12), dp(8));

        TextView hub = tab("Hub", false, false);
        hub.setOnClickListener(v -> {
            Ui.haptic(v);
            // Straight to the Hub in one tap, however deep the profile
            // stack is: CLEAR_TOP brings the existing MainActivity to
            // the front and clears every profile activity above it.
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
        });
        bar.addView(hub);

        List<ProfileStore.Profile> all = ProfileStore.load(this);
        for (ProfileStore.Profile p : all) {
            boolean active = p.slot == slot;
            TextView c = tab(p.name, active, active);
            if (!active) {
                c.setOnClickListener(v -> {
                    Ui.haptic(v);
                    pressFx(v);
                    Intent i = new Intent(this, ProfileStore.activityClassForSlot(p.slot));
                    i.putExtra(EXTRA_PROFILE_ID, p.id);
                    i.putExtra(EXTRA_PROFILE_NAME, p.name);
                    i.putExtra(EXTRA_SLOT, p.slot);
                    // Keep the stack flat: Main > current profile only.
                    i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    startActivity(i);
                    overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
                });
            }
            bar.addView(c);
        }

        TextView plus = tab("+", false, false);
        plus.setTextSize(16);
        plus.setOnClickListener(v -> {
            Ui.haptic(v);
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            i.putExtra("open_add", true);
            startActivity(i);
        });
        bar.addView(plus);

        TextView reload = tab("↻", false, false);
        reload.setOnClickListener(v -> {
            Ui.haptic(v);
            slept = false;
            if (webView != null) webView.loadUrl(ProfileStore.MUSE_URL);
        });
        bar.addView(reload);

        scroller.addView(bar);
        root.addView(scroller);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFF15B3DA));
        progress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF26262E));
        progress.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));
        root.addView(progress);

        stage = new FrameLayout(this);
        stage.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(stage);

        webView = new WebView(this);
        webView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // The visible profile owns the renderer; hidden profiles
            // waive theirs in onPause, so switching stays snappy.
            webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, false);
        }
        stage.addView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowContentAccess(true);
        s.setAllowFileAccess(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUserAgentString(DESKTOP_UA);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        }
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        webView.addJavascriptInterface(new BlobBridge(), "OsvynBridge");
        webView.addJavascriptInterface(new NotifyBridge(), "OsvynNotify");
        WatchService.start(this);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                String scheme = u.getScheme() == null ? "" : u.getScheme();
                if (scheme.equals("http") || scheme.equals("https")) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) {
                }
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                progress.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progress.setVisibility(View.GONE);
                CookieManager.getInstance().flush();
                if (url != null && url.contains("muse.ai")) {
                    view.evaluateJavascript(BRIDGE_JS, null);
                    view.evaluateJavascript(WATCH_JS, null);
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int p) {
                progress.setProgress(p);
                if (p >= 100) progress.setVisibility(View.GONE);
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try {
                    startActivityForResult(Intent.createChooser(i, "Choose file"), FILE_CHOOSER_REQUEST);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture,
                                          android.os.Message resultMsg) {
                // Real popup window in an overlay, keeping window.opener
                // alive. The old behaviour (load the popup URL in the main
                // view and destroy the popup) broke Muse connector OAuth
                // windows and artifact windows that talk back via
                // postMessage.
                closePopup();
                LinearLayout box = new LinearLayout(BaseProfileActivity.this);
                box.setOrientation(LinearLayout.VERTICAL);
                box.setBackgroundColor(0xFFFFFFFF);

                LinearLayout pbar = new LinearLayout(BaseProfileActivity.this);
                pbar.setOrientation(LinearLayout.HORIZONTAL);
                pbar.setGravity(Gravity.CENTER_VERTICAL);
                pbar.setBackgroundColor(0xFF101014);
                pbar.setPadding(dp(14), dp(8), dp(8), dp(8));
                TextView ptitle = new TextView(BaseProfileActivity.this);
                ptitle.setText("Muse window");
                ptitle.setTextColor(0xFFE8E6DF);
                ptitle.setTextSize(13.5f);
                ptitle.setLayoutParams(new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                pbar.addView(ptitle);
                TextView pclose = new TextView(BaseProfileActivity.this);
                pclose.setText("✕");
                pclose.setTextColor(0xFFE8E6DF);
                pclose.setTextSize(16);
                pclose.setPadding(dp(12), dp(4), dp(12), dp(4));
                pclose.setOnClickListener(v -> closePopup());
                pbar.addView(pclose);
                box.addView(pbar);

                WebView popup = new WebView(view.getContext());
                WebSettings ps = popup.getSettings();
                ps.setJavaScriptEnabled(true);
                ps.setDomStorageEnabled(true);
                ps.setDatabaseEnabled(true);
                ps.setSupportMultipleWindows(true);
                ps.setJavaScriptCanOpenWindowsAutomatically(true);
                ps.setUseWideViewPort(true);
                ps.setLoadWithOverviewMode(true);
                ps.setUserAgentString(DESKTOP_UA);
                CookieManager.getInstance().setAcceptThirdPartyCookies(popup, true);
                popup.addJavascriptInterface(new BlobBridge(), "OsvynBridge");
                popup.setWebViewClient(new WebViewClient());
                popup.setWebChromeClient(new WebChromeClient() {
                    @Override
                    public void onCloseWindow(WebView window) {
                        closePopup();
                    }
                });
                box.addView(popup, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

                stage.addView(box, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                popupView = popup;
                popupBox = box;

                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(popup);
                resultMsg.sendToTarget();
                return true;
            }

            @Override
            public void onCloseWindow(WebView window) {
                closePopup();
            }
        });

        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimeType, long contentLength) {
                String name = URLUtil.guessFileName(url, contentDisposition, mimeType);
                if (url != null && url.startsWith("blob:")) {
                    // Blob downloads go through the injected bridge, the
                    // same lesson the desktop build learned (its native
                    // download events for blob: never finished either).
                    String js = "window.__osvynUnduhUrl && window.__osvynUnduhUrl('"
                            + url.replace("'", "") + "','" + name.replace("'", "") + "')";
                    webView.evaluateJavascript(js, null);
                    Toast.makeText(BaseProfileActivity.this, "Downloading " + name, Toast.LENGTH_SHORT).show();
                    return;
                }
                try {
                    DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
                    req.setTitle(name);
                    req.setMimeType(mimeType);
                    req.addRequestHeader("User-Agent", userAgent);
                    String cookies = CookieManager.getInstance().getCookie(url);
                    if (cookies != null) req.addRequestHeader("Cookie", cookies);
                    req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
                    DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                    if (dm != null) dm.enqueue(req);
                    Toast.makeText(BaseProfileActivity.this, "Downloading " + name, Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(BaseProfileActivity.this,
                            "Download failed: " + e.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
                }
            }
        });

        setContentView(root);
    }

    /**
     * A desktop-bar pill tab: dot + label in one rounded pill.
     * awake=true draws the cyan presence dot, false the grey sleeping dot.
     * Tabs without a profile (Hub, +, reload) pass a label starting with a
     * space-free symbol and no dot is drawn for them.
     */
    private TextView tab(String text, boolean active, boolean awake) {
        boolean withDot = !(text.equals("Hub") || text.equals("+") || text.equals("↻"));
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13.5f);
        t.setLetterSpacing(0.03f);
        t.setTypeface(Fonts.semibold(this));
        if (active && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            t.setElevation(dp(3));
        }
        t.setTextColor(active ? 0xFF141419 : 0xFFB9B6C4);
        t.setSingleLine(true);
        android.graphics.drawable.StateListDrawable bg = new android.graphics.drawable.StateListDrawable();
        android.graphics.drawable.GradientDrawable pressed = new android.graphics.drawable.GradientDrawable();
        pressed.setColor(active ? 0xFFD8D5CC : 0xFF2C2C36);
        pressed.setCornerRadius(dp(999));
        pressed.setStroke(dp(1), active ? 0xFFD8D5CC : 0xFF33333D);
        android.graphics.drawable.GradientDrawable normal = new android.graphics.drawable.GradientDrawable();
        normal.setColor(active ? 0xFFE8E6DF : 0xFF1B1B21);
        normal.setCornerRadius(dp(999));
        normal.setStroke(dp(1), active ? 0xFFE8E6DF : 0xFF26262E);
        bg.addState(new int[]{android.R.attr.state_pressed}, pressed);
        bg.addState(new int[]{}, normal);
        t.setBackground(bg);
        if (withDot) {
            android.graphics.drawable.GradientDrawable dot = new android.graphics.drawable.GradientDrawable();
            dot.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            dot.setColor(awake ? 0xFF15B3DA : 0xFF8A8798);
            dot.setBounds(0, 0, dp(8), dp(8));
            t.setCompoundDrawables(dot, null, null, null);
            t.setCompoundDrawablePadding(dp(8));
            t.setPadding(dp(14), dp(7), dp(18), dp(7));
        } else {
            t.setPadding(dp(18), dp(7), dp(18), dp(7));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        t.setLayoutParams(lp);
        return t;
    }

    private void closePopup() {
        if (popupView != null && popupBox != null && stage != null) {
            stage.removeView(popupBox);
            popupView.destroy();
        }
        popupView = null;
        popupBox = null;
    }

    /**
     * Download bridge, the Android port of the desktop build's
     * jembatan_unduhan.js + simpan_unduhan_blob. Evidence from the desktop
     * (29 Oct test): muse.ai attachment downloads are blob: URLs and the
     * native download path receives the event but never finishes, on
     * WebView2 and on Android WebView alike. The injected script reads the
     * blob in the page and sends it here in base64 chunks; this side
     * assembles it and writes it to the phone's Downloads folder.
     */
    private final Map<String, StringBuilder> blobParts = new ConcurrentHashMap<>();
    private final Map<String, String[]> blobMeta = new ConcurrentHashMap<>();

    private class BlobBridge {
        @JavascriptInterface
        public void beginBlob(String id, String name, String mime, int total) {
            blobParts.put(id, new StringBuilder());
            blobMeta.put(id, new String[]{sanitizeName(name), mime == null ? "" : mime});
        }

        @JavascriptInterface
        public void blobChunk(String id, int index, String data) {
            StringBuilder sb = blobParts.get(id);
            if (sb != null && data != null) {
                synchronized (sb) {
                    sb.append(data);
                }
            }
        }

        @JavascriptInterface
        public void endBlob(String id) {
            StringBuilder sb = blobParts.remove(id);
            String[] meta = blobMeta.remove(id);
            if (sb == null || meta == null) return;
            try {
                byte[] bytes = Base64.decode(sb.toString(), Base64.DEFAULT);
                String where = saveDownload(meta[0], meta[1], bytes);
                toastOnUi("Saved to " + where);
            } catch (Exception e) {
                toastOnUi("Download failed: " + e.getClass().getSimpleName());
            }
        }
    }

    /**
     * Message watcher bridge. The injected script (WATCH_JS) watches
     * the Muse page for unread signals and reports here; this side
     * posts the sounding notification, but only while this profile is
     * not on screen, and never twice for the same unread state inside
     * 45 seconds.
     */
    private class NotifyBridge {
        @JavascriptInterface
        public void onUnread(int count, String sig, String snippet) {
            if (inForeground || mySlotValue <= 0) return;
            long now = System.currentTimeMillis();
            String s = sig == null ? "" : sig;
            if (s.equals(lastNotifiedSig) && now - lastNotifiedAt < 45_000L) return;
            lastNotifiedSig = s;
            lastNotifiedAt = now;
            Notify.postMessage(BaseProfileActivity.this, mySlotValue, myProfileId,
                    myProfileName, count, snippet);
        }

        @JavascriptInterface
        public void onAllRead() {
            lastNotifiedSig = "";
            if (mySlotValue > 0) Notify.cancelForSlot(BaseProfileActivity.this, mySlotValue);
        }
    }

    static final String WATCH_JS = """
            (function () {
              if (window.__osvynWatcher) return;
              window.__osvynWatcher = true;
              var lastSig = '';
              function scan() {
                try {
                  var count = 0;
                  var m = /^\\((\\d+)\\)/.exec(document.title || '');
                  if (m) count = parseInt(m[1], 10) || 0;
                  var badges = document.querySelectorAll('[aria-label*="unread" i], [data-testid*="unread" i]');
                  var badgeTotal = 0;
                  badges.forEach(function (el) {
                    var t = (el.getAttribute('aria-label') || el.textContent || '');
                    var n = /\\d+/.exec(t);
                    badgeTotal += n ? parseInt(n[0], 10) : 1;
                  });
                  if (badgeTotal > count) count = badgeTotal;
                  var sig = count + '|' + (document.title || '');
                  if (count > 0 && sig !== lastSig) {
                    lastSig = sig;
                    window.OsvynNotify && window.OsvynNotify.onUnread(count, sig, document.title || '');
                  } else if (count === 0 && lastSig !== '') {
                    lastSig = '';
                    window.OsvynNotify && window.OsvynNotify.onAllRead();
                  }
                } catch (e) {}
              }
              try {
                var obs = new MutationObserver(function () { scan(); });
                obs.observe(document.documentElement, {childList: true, subtree: true, attributes: true, characterData: true});
              } catch (e) {}
              setInterval(scan, 15000);
              setTimeout(scan, 3000);
            })();
            """;

    private String sanitizeName(String name) {
        if (name == null || name.trim().isEmpty()) return "unduhan";
        String n = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return n.isEmpty() ? "unduhan" : n;
    }

    private String saveDownload(String name, String mime, byte[] bytes) throws Exception {
        String type = (mime == null || mime.isEmpty()) ? "application/octet-stream" : mime;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
            cv.put(MediaStore.Downloads.MIME_TYPE, type);
            cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            cv.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new IllegalStateException("MediaStore insert failed");
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IllegalStateException("No output stream");
                out.write(bytes);
            }
            cv.clear();
            cv.put(MediaStore.Downloads.IS_PENDING, 0);
            getContentResolver().update(uri, cv, null, null);
            return "Downloads/" + name;
        }
        File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) throw new IllegalStateException("No download dir");
        File f = new File(dir, name);
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(bytes);
        }
        return f.getAbsolutePath();
    }

    private void toastOnUi(String msg) {
        runOnUiThread(() -> Toast.makeText(BaseProfileActivity.this, msg, Toast.LENGTH_LONG).show());
    }

    static final String BRIDGE_JS = """
            (function () {
              if (window.__osvynBridge) return;
              window.__osvynBridge = true;
              var BATAS = 40 * 1024 * 1024;
              var seq = 0;
              function kirim(nama, blob) {
                var pembaca = new FileReader();
                pembaca.onload = function () {
                  var hasil = String(pembaca.result || '');
                  var koma = hasil.indexOf(',');
                  var b64 = koma >= 0 ? hasil.slice(koma + 1) : '';
                  var id = 'dl' + (++seq) + '_' + Date.now();
                  var CH = 200000;
                  var total = Math.max(1, Math.ceil(b64.length / CH));
                  try {
                    window.OsvynBridge.beginBlob(id, nama, blob.type || '', total);
                    for (var i = 0; i < total; i++) {
                      window.OsvynBridge.blobChunk(id, i, b64.substring(i * CH, (i + 1) * CH));
                    }
                    window.OsvynBridge.endBlob(id);
                  } catch (e) { console.warn('osvyn bridge fail:', e); }
                };
                pembaca.readAsDataURL(blob);
              }
              function namaDari(blob, a) {
                var nama = a ? (a.getAttribute('download') || a.download || '') : '';
                if (nama) return nama;
                var peta = {'text/plain':'txt','application/pdf':'pdf','image/png':'png','image/jpeg':'jpg','image/webp':'webp','application/zip':'zip','application/json':'json','text/markdown':'md','text/csv':'csv'};
                var ekst = peta[blob.type] || (blob.type.split('/')[1] || '').split('+')[0].replace(/[^a-z0-9]/gi, '');
                return 'unduhan' + (ekst ? '.' + ekst : '');
              }
              window.__osvynUnduhUrl = function (u, nama) {
                fetch(u).then(function (r) { return r.blob(); }).then(function (b) {
                  kirim(nama || namaDari(b, null), b);
                }).catch(function () {});
              };
              var klikAsli = HTMLAnchorElement.prototype.click;
              function tangani(a, programatis) {
                var href = a.getAttribute('href') || '';
                if (href.indexOf('blob:') !== 0) return false;
                var mau = programatis || a.hasAttribute('download') || (a.download || '') !== '' || !document.contains(a);
                if (!mau) return false;
                fetch(href).then(function (r) { return r.blob(); }).then(function (blob) {
                  if (blob.size > BATAS) { klikAsli.call(a); return; }
                  kirim(namaDari(blob, a), blob);
                }).catch(function () {});
                return true;
              }
              document.addEventListener('click', function (ev) {
                var a = ev.target;
                while (a && a.tagName !== 'A') a = a.parentElement;
                if (!a) return;
                if (tangani(a, false)) { ev.preventDefault(); ev.stopImmediatePropagation(); }
              }, true);
              HTMLAnchorElement.prototype.click = function () {
                if (tangani(this, true)) return;
                return klikAsli.apply(this, arguments);
              };
            })();
            """;

    /** Desktop tab press feedback: scale to .96 and back. */
    private void pressFx(View v) {
        v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(70)
                .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).setDuration(110).start())
                .start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQUEST && fileCallback != null) {
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int n = data.getClipData().getItemCount();
                    results = new Uri[n];
                    for (int i = 0; i < n; i++) results[i] = data.getClipData().getItemAt(i).getUri();
                } else if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
            }
            fileCallback.onReceiveValue(results);
            fileCallback = null;
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        handler.removeCallbacks(sleepRunnable);
        if (slept && webView != null) {
            slept = false;
            webView.onResume();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        handler.postDelayed(sleepRunnable, DEEP_SLEEP_MS);
    }

    @Override
    protected void onResume() {
        super.onResume();
        inForeground = true;
        if (mySlotValue > 0) {
            // Reading the profile clears its message notification.
            Notify.cancelForSlot(this, mySlotValue);
            lastNotifiedSig = "";
        }
        if (webView != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, false);
            }
            webView.onResume();
        }
    }

    @Override
    protected void onPause() {
        inForeground = false;
        if (webView != null) {
            // Deliberately NO webView.onPause() here: pausing the page
            // would pause its JavaScript and silence the message
            // watcher. Only the renderer is waived, so the page stays
            // alive to hear new messages while costing little GPU.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Yield the renderer while hidden so the profile the
                // user switched to gets full GPU and CPU.
                webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, true);
            }
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(sleepRunnable);
        closePopup();
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
