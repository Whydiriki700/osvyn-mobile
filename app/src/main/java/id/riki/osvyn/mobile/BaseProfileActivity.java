package id.riki.osvyn.mobile;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
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
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
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
import java.util.ArrayList;
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
    private static final int MEDIA_PERMISSION_REQUEST = 1002;

    protected abstract int mySlot();

    private WebView webView;
    private FrameLayout stage;
    private WebView popupView;
    private View popupBox;
    private View errorBox;
    private String failedUrl;
    private FrameLayout progressTrack;
    private View progressFill;
    private android.animation.ValueAnimator progressAnim;
    private float progressFraction = 0f;
    private boolean slept = false;
    private ValueCallback<Uri[]> fileCallback;
    private PermissionRequest pendingMediaRequest;
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

    // Call Mode (1.4.4): while ANOTHER profile is in a call, a
    // backgrounded profile freezes for real (page paused, watcher
    // disconnected) so the call gets the whole phone. The accepted
    // trade-off, approved by Riki: this profile's message
    // notifications pause for the length of the call and resume when
    // it ends. The check runs only while backgrounded, every 3 s,
    // against the shared call marker (CallMode); a stale marker
    // (caller died mid-call) unfreezes automatically.
    private boolean callFrozen = false;
    private final Runnable callCheckRunnable = new Runnable() {
        @Override public void run() {
            if (inForeground || webView == null) return;
            // Manual sleep (1.7.0): the Hub asked this profile to rest.
            // Honoured here, in the background-only check, and skipped
            // while this profile owns a call. Sleeping is real: the
            // page pauses and the activity finishes, so the WebView
            // and its renderer are destroyed, not parked. The watcher
            // for this profile stops hearing messages until it is
            // opened again, which is the point of putting it to sleep.
            if (mySlotValue > 0
                    && HubState.sleepRequestedAt(BaseProfileActivity.this, mySlotValue) > 0
                    && !myProfileId.equals(CallMode.freshOwner(BaseProfileActivity.this))) {
                HubState.consumeSleepRequest(BaseProfileActivity.this, mySlotValue);
                slept = true;
                webView.onPause();
                applyWatcherSuspend();
                finish();
                return;
            }
            boolean otherInCall = CallMode.freshOwnedByOther(
                    BaseProfileActivity.this, myProfileId);
            if (otherInCall && !callFrozen) {
                callFrozen = true;
                webView.onPause();
                applyWatcherSuspend();
            } else if (!otherInCall && callFrozen) {
                callFrozen = false;
                webView.onResume();
                applyWatcherSuspend();
            }
            handler.postDelayed(this, 3000);
        }
    };

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

        // Loading line (1.6.0): a custom 3dp track + cyan fill whose
        // width eases between progress values on the locked curve,
        // instead of the stock ProgressBar's stepped jumps.
        progressTrack = new FrameLayout(this);
        progressTrack.setBackgroundColor(0xFF26262E);
        progressTrack.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));
        progressFill = new View(this);
        progressFill.setBackgroundColor(0xFF15B3DA);
        progressTrack.addView(progressFill, new FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(progressTrack);

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
        webView.addJavascriptInterface(new CallBridge(), "OsvynCall");
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
                showProgress(0);
                hidePageError();
                // A fresh page load ends any previous call context.
                clearCallAudio();
            }

            // Page error handling (1.7.0): before this, a failed load
            // (a flaky network moment while opening a web page from a
            // Muse chat) landed on the WebView's bare system error
            // page with no way back, which is the dead-end try/retry
            // screen Riki kept hitting. Now a failed main-frame load
            // gets an Osvyn error card with a real reload button.
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                if (request.isForMainFrame()) {
                    showPageError(request.getUrl().toString(),
                            String.valueOf(error.getDescription()));
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                            WebResourceResponse response) {
                if (request.isForMainFrame() && response.getStatusCode() >= 500) {
                    showPageError(request.getUrl().toString(),
                            "Server error " + response.getStatusCode());
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                hideProgress();
                CookieManager.getInstance().flush();
                if (url != null && url.contains("muse.ai")) {
                    view.evaluateJavascript(BRIDGE_JS, null);
                    view.evaluateJavascript(WATCH_JS, null);
                    view.evaluateJavascript(CALL_JS, null);
                    applyWatcherSuspend();
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int p) {
                setProgressSmooth(p);
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                handleMediaPermission(request);
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
                // Popup client (1.7.0): the popup used to get a bare
                // WebViewClient, so a failed page inside a Muse
                // window (an opened web page, a connector OAuth) died
                // on the system error page and its downloads went
                // nowhere. It now gets the download bridge injection,
                // the shared download handler, and its own error bar
                // with a working retry.
                popup.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageFinished(WebView v, String url) {
                        if (url != null && url.contains("muse.ai")) {
                            v.evaluateJavascript(BRIDGE_JS, null);
                        }
                    }

                    @Override
                    public void onReceivedError(WebView v, WebResourceRequest request,
                                                WebResourceError error) {
                        if (request.isForMainFrame()) {
                            showPopupError(box, popup, request.getUrl().toString(),
                                    String.valueOf(error.getDescription()));
                        }
                    }

                    @Override
                    public void onReceivedHttpError(WebView v, WebResourceRequest request,
                                                    WebResourceResponse response) {
                        if (request.isForMainFrame() && response.getStatusCode() >= 500) {
                            showPopupError(box, popup, request.getUrl().toString(),
                                    "Server error " + response.getStatusCode());
                        }
                    }
                });
                popup.setDownloadListener(new DownloadListener() {
                    @Override
                    public void onDownloadStart(String url, String userAgent,
                                                String contentDisposition, String mimeType,
                                                long contentLength) {
                        handleDownload(popup, url, userAgent, contentDisposition,
                                mimeType, contentLength);
                    }
                });
                popup.setWebChromeClient(new WebChromeClient() {
                    @Override
                    public void onCloseWindow(WebView window) {
                        closePopup();
                    }

                    @Override
                    public void onPermissionRequest(PermissionRequest request) {
                        handleMediaPermission(request);
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
                handleDownload(webView, url, userAgent, contentDisposition,
                        mimeType, contentLength);
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

    private void showProgress(int p) {
        if (progressAnim != null) progressAnim.cancel();
        progressFraction = 0f;
        progressTrack.setVisibility(View.VISIBLE);
        setFillFraction(0f);
        if (p > 0) setProgressSmooth(p);
    }

    private void setProgressSmooth(int p) {
        float target = p / 100f;
        progressTrack.setVisibility(View.VISIBLE);
        if (!Ui.motionOk(this)) {
            progressFraction = target;
            setFillFraction(target);
            if (p >= 100) hideProgress();
            return;
        }
        if (progressAnim != null) progressAnim.cancel();
        final float from = progressFraction;
        progressAnim = android.animation.ValueAnimator.ofFloat(from, target);
        progressAnim.setDuration(240);
        progressAnim.setInterpolator(Ui.EASE);
        progressAnim.addUpdateListener(a -> {
            progressFraction = (float) a.getAnimatedValue();
            setFillFraction(progressFraction);
        });
        progressAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                if (progressFraction >= 0.999f) hideProgress();
            }
        });
        progressAnim.start();
    }

    private void setFillFraction(float f) {
        int w = progressTrack.getWidth();
        if (w <= 0) return;
        FrameLayout.LayoutParams fl = (FrameLayout.LayoutParams) progressFill.getLayoutParams();
        fl.width = (int) (w * f);
        progressFill.setLayoutParams(fl);
    }

    private void hideProgress() {
        if (progressAnim != null) progressAnim.cancel();
        progressTrack.setVisibility(View.GONE);
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
                // Download history (1.5.0): the Hub lists what came
                // down through the app and reopens it from there.
                HubState.addDownload(BaseProfileActivity.this, meta[0], meta[1],
                        bytes.length, myProfileName);
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
            // The shared unread count feeds the Hub badge and the home
            // screen widget (1.5.0), so it is recorded even when the
            // sounding notification below is suppressed or deduped.
            if (mySlotValue > 0) {
                HubState.setUnread(BaseProfileActivity.this, mySlotValue, count);
                UnreadWidget.refresh(BaseProfileActivity.this);
            }
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
            if (mySlotValue > 0) {
                HubState.setUnread(BaseProfileActivity.this, mySlotValue, 0);
                UnreadWidget.refresh(BaseProfileActivity.this);
                Notify.cancelForSlot(BaseProfileActivity.this, mySlotValue);
            }
        }
    }

    /** Call state bridge: the page reports whether a call is live in
     *  this profile; the marker lets every other profile freeze while
     *  this one is in a call (Call Mode, 1.4.4). */
    private class CallBridge {
        @JavascriptInterface
        public void onCallState(boolean active) {
            CallMode.setActive(BaseProfileActivity.this, myProfileId, active);
        }
    }

    /**
     * Call detection for Call Mode (1.4.4). Wraps getUserMedia and
     * counts live media tracks: while at least one track is live this
     * profile is in a call, and the native side marks it so the other
     * profiles can freeze. Track stop() does not fire 'ended' in
     * Chrome, so the heartbeat recounts by readyState instead of
     * trusting events alone.
     */
    static final String CALL_JS = """
            (function () {
              if (window.__osvynCallHook) return;
              window.__osvynCallHook = true;
              var tracks = [];
              var reported = false;
              var hb = null;
              function liveCount() {
                var n = 0;
                for (var i = tracks.length - 1; i >= 0; i--) {
                  if (tracks[i].readyState === 'live') n++;
                  else tracks.splice(i, 1);
                }
                return n;
              }
              function push(active) {
                try { window.OsvynCall && window.OsvynCall.onCallState(active); } catch (e) {}
              }
              function report() {
                var active = liveCount() > 0;
                if (active !== reported) { reported = active; push(active); }
                if (active && !hb) hb = setInterval(tick, 5000);
                if (!active && hb) { clearInterval(hb); hb = null; }
              }
              function tick() {
                var active = liveCount() > 0;
                reported = active;
                push(active);
                if (!active && hb) { clearInterval(hb); hb = null; }
              }
              function watch(stream) {
                try {
                  stream.getTracks().forEach(function (t) {
                    if (tracks.indexOf(t) < 0) {
                      tracks.push(t);
                      t.addEventListener('ended', report);
                    }
                  });
                } catch (e) {}
                report();
              }
              try {
                var md = navigator.mediaDevices;
                if (md && typeof md.getUserMedia === 'function') {
                  var orig = md.getUserMedia.bind(md);
                  md.getUserMedia = function (constraints) {
                    return orig(constraints).then(function (s) { watch(s); return s; });
                  };
                }
              } catch (e) {}
            })();
            """;

    static final String WATCH_JS = """
            (function () {
              if (window.__osvynWatcher) return;
              window.__osvynWatcher = true;
              var lastSig = '';
              var scheduled = false;
              var suspended = false;
              // Smoothness (1.4.1): scans are coalesced to at most one per
              // 1.5 s. The old observer ran a full-document scan on EVERY
              // DOM mutation, and Muse mutates constantly while a reply
              // streams, so the watcher itself was a main-thread load
              // fighting the page it watches.
              // Call weight (1.4.3): while this profile is on screen the
              // watcher is suspended entirely. Its notifications are
              // suppressed in the foreground anyway (see NotifyBridge),
              // so scanning during a call was pure cost: the call page
              // mutates constantly (waveform, timers) and every scan
              // fought the call's own rendering. The native side flips
              // this flag on resume/pause; background watching, the only
              // state where notifications matter, is unchanged.
              // Stage 1 (1.4.4): suspension now disconnects the observers
              // and clears the interval for real. In 1.4.3 the flag only
              // made schedule()/scan() return early, so both observers
              // stayed connected and their callbacks kept firing on every
              // mutation of the call page for the whole call.
              var obs = null;
              var titleObs = null;
              var timer = null;
              // Eco mode (1.5.0): the Hub switch stretches the periodic
              // scan from 15 s to 60 s. The observer path stays as is,
              // so a visible change is still noticed quickly; what gets
              // rarer is the blind re-scan, which is the battery cost.
              var scanMs = 15000;
              window.__osvynSetEco = function (v) {
                scanMs = v ? 60000 : 15000;
                if (timer !== null) {
                  clearInterval(timer);
                  timer = setInterval(scan, scanMs);
                }
              };
              function startWatching() {
                if (obs) return;
                try {
                  obs = new MutationObserver(schedule);
                  obs.observe(document.documentElement, {childList: true, subtree: true});
                  var titleEl = document.querySelector('title');
                  if (titleEl) {
                    titleObs = new MutationObserver(schedule);
                    titleObs.observe(titleEl, {childList: true, characterData: true});
                  }
                } catch (e) {}
                timer = setInterval(scan, scanMs);
              }
              function stopWatching() {
                if (obs) { try { obs.disconnect(); } catch (e) {} obs = null; }
                if (titleObs) { try { titleObs.disconnect(); } catch (e) {} titleObs = null; }
                if (timer !== null) { clearInterval(timer); timer = null; }
              }
              window.__osvynSetWatcherSuspended = function (v) {
                suspended = !!v;
                if (suspended) stopWatching();
                else { startWatching(); schedule(); }
              };
              function schedule() {
                if (suspended || scheduled) return;
                scheduled = true;
                setTimeout(function () { scheduled = false; scan(); }, 1500);
              }
              function scan() {
                if (suspended) return;
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
              startWatching();
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
            // Download folder (1.7.0): the Hub setting decides where
            // blob downloads land, no longer nailed to Downloads.
            cv.put(MediaStore.Downloads.RELATIVE_PATH, HubState.dlFolder(this));
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
            return HubState.dlFolderLabel(this) + "/" + name;
        }
        File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) throw new IllegalStateException("No download dir");
        File f = new File(dir, name);
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(bytes);
        }
        return f.getAbsolutePath();
    }

    /**
     * Shared download handling for the main view and popup windows
     * (1.7.0: extracted so popup downloads stop going nowhere).
     * Blob URLs go through the injected bridge; anything else goes to
     * the DownloadManager, landing in the folder picked in the Hub.
     */
    private void handleDownload(WebView src, String url, String userAgent,
                                String contentDisposition, String mimeType,
                                long contentLength) {
        String name = URLUtil.guessFileName(url, contentDisposition, mimeType);
        if (url != null && url.startsWith("blob:")) {
            // Blob downloads go through the injected bridge, the
            // same lesson the desktop build learned (its native
            // download events for blob: never finished either).
            String js = "window.__osvynUnduhUrl && window.__osvynUnduhUrl('"
                    + url.replace("'", "") + "','" + name.replace("'", "") + "')";
            src.evaluateJavascript(js, null);
            Toast.makeText(this, "Downloading " + name, Toast.LENGTH_SHORT).show();
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
            setDlDestination(req, name);
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm != null) dm.enqueue(req);
            Toast.makeText(this, "Downloading " + name, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this,
                    "Download failed: " + e.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
        }
    }

    /** Maps the Hub's chosen download folder to a DownloadManager destination. */
    private void setDlDestination(DownloadManager.Request req, String name) {
        switch (HubState.dlFolder(this)) {
            case "Download/Osvyn":
                req.setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS, "Osvyn/" + name);
                break;
            case "Documents":
                req.setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOCUMENTS, name);
                break;
            case "Pictures":
                req.setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_PICTURES, name);
                break;
            case "Movies":
                req.setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_MOVIES, name);
                break;
            case "Music":
                req.setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_MUSIC, name);
                break;
            default:
                req.setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS, name);
        }
    }

    // ---------- page error card (1.7.0) ----------

    private void showPageError(String url, String desc) {
        if (stage == null || webView == null) return;
        hidePageError();
        failedUrl = url;
        FrameLayout box = new FrameLayout(this);
        box.setBackgroundColor(0xFF101014);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(36), dp(24), dp(36), dp(24));
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.gravity = Gravity.CENTER;
        box.addView(col, clp);

        TextView title = new TextView(this);
        title.setText("This page could not be loaded");
        title.setTextColor(0xFFE8E6DF);
        title.setTextSize(17);
        title.setTypeface(Fonts.semibold(this));
        title.setGravity(Gravity.CENTER);
        col.addView(title);

        String host = "";
        try {
            String h = Uri.parse(url).getHost();
            if (h != null) host = h;
        } catch (Exception ignored) {
        }
        TextView sub = new TextView(this);
        sub.setText((desc == null ? "Connection problem" : desc)
                + (host.isEmpty() ? "" : "\n" + host));
        sub.setTextColor(0xFFB9B6C4);
        sub.setTextSize(13);
        sub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(8);
        sub.setLayoutParams(slp);
        col.addView(sub);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(20);
        row.setLayoutParams(rlp);
        col.addView(row);

        TextView retry = new TextView(this);
        retry.setText("Try again");
        retry.setTextColor(0xFFFFFFFF);
        retry.setTextSize(14);
        retry.setTypeface(Fonts.semibold(this));
        android.graphics.drawable.GradientDrawable rbg =
                new android.graphics.drawable.GradientDrawable();
        rbg.setColor(0xFF2B6FF2);
        rbg.setCornerRadius(dp(10));
        retry.setBackground(rbg);
        retry.setPadding(dp(20), dp(11), dp(20), dp(11));
        retry.setOnClickListener(v -> {
            String u = failedUrl;
            hidePageError();
            if (webView != null && u != null) webView.loadUrl(u);
        });
        row.addView(retry);

        TextView back = new TextView(this);
        back.setText("Back to Muse");
        back.setTextColor(0xFFE8E6DF);
        back.setTextSize(14);
        back.setTypeface(Fonts.semibold(this));
        android.graphics.drawable.GradientDrawable bbg =
                new android.graphics.drawable.GradientDrawable();
        bbg.setColor(0xFF1B1B21);
        bbg.setStroke(dp(1), 0xFF26262E);
        bbg.setCornerRadius(dp(10));
        back.setBackground(bbg);
        back.setPadding(dp(20), dp(11), dp(20), dp(11));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.leftMargin = dp(10);
        back.setLayoutParams(blp);
        back.setOnClickListener(v -> {
            hidePageError();
            if (webView != null) webView.loadUrl(ProfileStore.MUSE_URL);
        });
        row.addView(back);

        stage.addView(box, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        errorBox = box;
        if (Ui.motionOk(this)) {
            box.setAlpha(0f);
            box.animate().alpha(1f).setDuration(180).setInterpolator(Ui.EASE).start();
        }
    }

    private void hidePageError() {
        if (errorBox != null && stage != null) stage.removeView(errorBox);
        errorBox = null;
    }

    /** Compact error bar pinned under a popup window, with its own retry. */
    private void showPopupError(LinearLayout box, WebView popup, String url, String desc) {
        if (box == null || popup == null) return;
        View old = box.findViewWithTag("osvyn_popup_error");
        if (old != null) box.removeView(old);
        LinearLayout bar = new LinearLayout(this);
        bar.setTag("osvyn_popup_error");
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xFF1B1B21);
        bar.setPadding(dp(14), dp(9), dp(8), dp(9));

        TextView msg = new TextView(this);
        msg.setText("Page failed to load: " + (desc == null ? "connection problem" : desc));
        msg.setTextColor(0xFFE8E6DF);
        msg.setTextSize(12.5f);
        msg.setMaxLines(2);
        bar.addView(msg, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView retry = new TextView(this);
        retry.setText("Try again");
        retry.setTextColor(0xFF7FB0FF);
        retry.setTextSize(13);
        retry.setTypeface(Fonts.semibold(this));
        retry.setPadding(dp(12), dp(6), dp(12), dp(6));
        retry.setOnClickListener(v -> {
            box.removeView(bar);
            popup.loadUrl(url);
        });
        bar.addView(retry);

        box.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
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

    /** Tell the in-page watcher whether this profile is on screen.
     *  Suspended while visible (notifications are suppressed then
     *  anyway), live while backgrounded. See WATCH_JS (1.4.3). */
    private void applyWatcherSuspend() {
        if (webView == null) return;
        webView.evaluateJavascript(
                "window.__osvynSetWatcherSuspended && window.__osvynSetWatcherSuspended("
                        + ((inForeground || callFrozen) ? "true" : "false") + ")", null);
        applyEco();
    }

    /** Eco mode (1.5.0): pass the Hub switch into the watcher. Read
     *  fresh from the shared state on every lifecycle pass, so the
     *  switch takes effect the next time a profile is opened or
     *  backgrounded, with no restart. */
    private void applyEco() {
        if (webView == null) return;
        webView.evaluateJavascript(
                "window.__osvynSetEco && window.__osvynSetEco("
                        + (HubState.eco(this) ? "true" : "false") + ")", null);
    }

    /**
     * WebRTC media permissions: voice and video calls inside Muse.
     * WebView refuses getUserMedia unless the app both holds the matching
     * Android runtime permission and grants the request here. Before
     * 1.4.0 neither existed, so the call screen only ever showed the
     * site's "allow microphone access" notice. Flow: grant at once when
     * the runtime permissions are already held; otherwise ask Android
     * first and answer the page from onRequestPermissionsResult.
     */
    private void handleMediaPermission(PermissionRequest request) {
        boolean wantAudio = false;
        boolean wantVideo = false;
        for (String r : request.getResources()) {
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) wantAudio = true;
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) wantVideo = true;
        }
        if (!wantAudio && !wantVideo) {
            request.deny();
            return;
        }
        List<String> missing = new ArrayList<>();
        if (wantAudio && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.RECORD_AUDIO);
        }
        if (wantVideo && checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.CAMERA);
        }
        if (wantAudio && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED
                && bluetoothCallDevicePresent()) {
            // Asked only when a Bluetooth call device is actually
            // connected, so the prompt stays contextual to the call.
            missing.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (missing.isEmpty()) {
            request.grant(request.getResources());
            if (wantAudio) routeCallAudio();
            return;
        }
        if (pendingMediaRequest != null) {
            // One system prompt at a time. A request arriving while the
            // prompt is up is denied; the page can ask again afterwards.
            request.deny();
            return;
        }
        pendingMediaRequest = request;
        requestPermissions(missing.toArray(new String[0]), MEDIA_PERMISSION_REQUEST);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != MEDIA_PERMISSION_REQUEST || pendingMediaRequest == null) return;
        PermissionRequest req = pendingMediaRequest;
        pendingMediaRequest = null;
        // Re-check the live permission state instead of trusting the
        // result arrays: a permission already held before the prompt is
        // not listed in them, and an interrupted prompt returns empty
        // arrays.
        boolean audioOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        boolean videoOk = checkSelfPermission(Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
        List<String> granted = new ArrayList<>();
        for (String r : req.getResources()) {
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r) && audioOk) granted.add(r);
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r) && videoOk) granted.add(r);
        }
        if (granted.isEmpty()) {
            req.deny();
        } else {
            req.grant(granted.toArray(new String[0]));
            if (audioOk) routeCallAudio();
        }
    }

    /**
     * Bluetooth call routing (1.4.2). WebView on its own never moves a
     * call to a Bluetooth headset: the page gets the microphone, but the
     * audio stays on the phone. When a call asks for the microphone and
     * a Bluetooth call device is connected, the app selects it as the
     * communication device (Android 12+ API; classic Bluetooth SCO
     * start below that). Routing is cleared when the page reloads or
     * the profile closes, which is where a call context ends.
     */
    private boolean bluetoothCallDevicePresent() {
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                for (AudioDeviceInfo d : am.getAvailableCommunicationDevices()) {
                    if (d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) return true;
                }
                return false;
            }
            return am.isBluetoothScoAvailableOffCall();
        } catch (Exception e) {
            return false;
        }
    }

    private void routeCallAudio() {
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) return;
                for (AudioDeviceInfo d : am.getAvailableCommunicationDevices()) {
                    if (d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                        am.setCommunicationDevice(d);
                        return;
                    }
                }
            } else if (am.isBluetoothScoAvailableOffCall()) {
                am.startBluetoothSco();
                am.setBluetoothScoOn(true);
            }
        } catch (Exception ignored) {
        }
    }

    private void clearCallAudio() {
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.clearCommunicationDevice();
            } else {
                am.setBluetoothScoOn(false);
                am.stopBluetoothSco();
            }
        } catch (Exception ignored) {
        }
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
        // App lock gate (1.7.0): profiles ask for the PIN themselves
        // after a long backgrounding, not only Splash at cold start.
        if (!LockGate.enforceIfNeeded(this)) {
            HubState.noteLockForeground(this);
        }
        inForeground = true;
        // Foreground again: Call Mode no longer applies to this
        // profile, and its freeze check stops.
        handler.removeCallbacks(callCheckRunnable);
        callFrozen = false;
        if (mySlotValue > 0) {
            // Reading the profile clears its message notification.
            Notify.cancelForSlot(this, mySlotValue);
            HubState.setUnread(this, mySlotValue, 0);
            UnreadWidget.refresh(this);
            lastNotifiedSig = "";
        }
        if (webView != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, false);
            }
            webView.onResume();
            applyWatcherSuspend();
        }
    }

    @Override
    protected void onPause() {
        inForeground = false;
        HubState.noteLockBackground(this);
        if (webView != null) {
            applyWatcherSuspend();
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
        // Backgrounded: watch for another profile's call (Call Mode).
        handler.removeCallbacks(callCheckRunnable);
        handler.postDelayed(callCheckRunnable, 3000);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(sleepRunnable);
        handler.removeCallbacks(callCheckRunnable);
        // If this profile owned the call marker, its call is over.
        CallMode.setActive(this, myProfileId, false);
        clearCallAudio();
        closePopup();
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // While the lock gate covers the screen, Back must not walk
        // the page history behind it.
        if (LockGate.isShowing(this)) return;
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
