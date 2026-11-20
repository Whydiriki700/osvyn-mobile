package id.riki.osvyn.mobile;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Calendar;
import java.util.Properties;

/**
 * Shared app state for the 1.5.0 Hub features, kept in one properties
 * file in the app files dir. Every process of this app (Hub, watcher
 * service, each profile) reads and writes the same file, so this is
 * the one place cross-process facts live: unread counts per slot,
 * quiet hours, eco mode, the manual freeze switch, the app lock, the
 * last update check, and the download history (a sibling JSON file).
 * Writes are atomic (temp file, then rename), the same pattern
 * ProfileStore uses, so a kill mid-write never corrupts the state.
 */
public final class HubState {

    private static final String FILE = "hub_state.properties";
    private static final String DL_FILE = "downloads.json";
    private static final int DL_CAP = 100;

    private HubState() {}

    // ---------- raw properties ----------

    private static synchronized Properties load(Context ctx) {
        Properties p = new Properties();
        File f = new File(ctx.getFilesDir(), FILE);
        if (f.exists()) {
            try (FileInputStream in = new FileInputStream(f)) {
                p.load(in);
            } catch (Exception ignored) {
            }
        }
        return p;
    }

    private static synchronized void save(Context ctx, Properties p) {
        try {
            File tmp = new File(ctx.getFilesDir(), FILE + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                p.store(out, null);
            }
            File target = new File(ctx.getFilesDir(), FILE);
            if (!tmp.renameTo(target)) {
                try (FileOutputStream out = new FileOutputStream(target)) {
                    p.store(out, null);
                }
                tmp.delete();
            }
        } catch (Exception ignored) {
        }
    }

    static String get(Context ctx, String key, String def) {
        String v = load(ctx).getProperty(key);
        return v == null ? def : v;
    }

    static synchronized void set(Context ctx, String key, String value) {
        Properties p = load(ctx);
        p.setProperty(key, value);
        save(ctx, p);
    }

    static boolean getBool(Context ctx, String key) {
        return "1".equals(get(ctx, key, "0"));
    }

    static void setBool(Context ctx, String key, boolean v) {
        set(ctx, key, v ? "1" : "0");
    }

    static int getInt(Context ctx, String key, int def) {
        try {
            return Integer.parseInt(get(ctx, key, String.valueOf(def)));
        } catch (Exception e) {
            return def;
        }
    }

    static long getLong(Context ctx, String key, long def) {
        try {
            return Long.parseLong(get(ctx, key, String.valueOf(def)));
        } catch (Exception e) {
            return def;
        }
    }

    // ---------- unread counts (Hub badges + widget) ----------

    static void setUnread(Context ctx, int slot, int count) {
        set(ctx, "unread." + slot, String.valueOf(Math.max(0, count)));
    }

    static int unread(Context ctx, int slot) {
        return getInt(ctx, "unread." + slot, 0);
    }

    static int totalUnread(Context ctx) {
        int total = 0;
        for (int s = 1; s <= ProfileStore.MAX_PROFILES; s++) total += unread(ctx, s);
        return total;
    }

    // ---------- quiet hours ----------

    static boolean quietEnabled(Context ctx) {
        return getBool(ctx, "quiet.enabled");
    }

    static int quietStartMin(Context ctx) {
        return getInt(ctx, "quiet.startMin", 22 * 60);
    }

    static int quietEndMin(Context ctx) {
        return getInt(ctx, "quiet.endMin", 7 * 60);
    }

    static void setQuietWindow(Context ctx, int startMin, int endMin) {
        Properties p = load(ctx);
        p.setProperty("quiet.startMin", String.valueOf(startMin));
        p.setProperty("quiet.endMin", String.valueOf(endMin));
        save(ctx, p);
    }

    /** True when quiet hours are switched on and now is inside them.
     *  The window may wrap midnight (22:00 to 07:00). */
    static boolean quietNow(Context ctx) {
        if (!quietEnabled(ctx)) return false;
        int start = quietStartMin(ctx);
        int end = quietEndMin(ctx);
        Calendar c = Calendar.getInstance();
        int now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        if (start <= end) return now >= start && now < end;
        return now >= start || now < end;
    }

    static String fmtMin(int min) {
        int h = (min / 60) % 24;
        int m = min % 60;
        return (h < 10 ? "0" : "") + h + ":" + (m < 10 ? "0" : "") + m;
    }

    // ---------- eco mode + manual freeze ----------

    static boolean eco(Context ctx) {
        return getBool(ctx, "eco.enabled");
    }

    static boolean manualFreeze(Context ctx) {
        return getBool(ctx, "freeze.manual");
    }

    // ---------- app lock ----------

    static boolean hasPin(Context ctx) {
        return !get(ctx, "lock.pinHash", "").isEmpty();
    }

    static boolean lockEnabled(Context ctx) {
        return getBool(ctx, "lock.enabled") && hasPin(ctx);
    }

    static void setPin(Context ctx, String pin) {
        set(ctx, "lock.pinHash", sha256Hex("osvyn-lock:" + pin));
        setBool(ctx, "lock.enabled", true);
    }

    static boolean checkPin(Context ctx, String pin) {
        String h = get(ctx, "lock.pinHash", "");
        return !h.isEmpty() && h.equals(sha256Hex("osvyn-lock:" + pin));
    }

    static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    // ---------- update check ----------

    static String latestVersion(Context ctx) {
        return get(ctx, "update.latest", "");
    }

    static void setLatestVersion(Context ctx, String v, long checkedAt) {
        Properties p = load(ctx);
        p.setProperty("update.latest", v);
        p.setProperty("update.checkedAt", String.valueOf(checkedAt));
        save(ctx, p);
    }

    static long updateCheckedAt(Context ctx) {
        return getLong(ctx, "update.checkedAt", 0);
    }

    /** Dotted-version compare: >0 when a is newer than b. */
    static int compareVersions(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            int na = i < pa.length ? parseNum(pa[i]) : 0;
            int nb = i < pb.length ? parseNum(pb[i]) : 0;
            if (na != nb) return na - nb;
        }
        return 0;
    }

    private static int parseNum(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    // ---------- download history ----------

    static synchronized void addDownload(Context ctx, String name, String mime,
                                         long size, String profile) {
        try {
            JSONArray arr = loadDownloads(ctx);
            JSONObject o = new JSONObject();
            o.put("name", name);
            o.put("mime", mime == null ? "" : mime);
            o.put("size", size);
            o.put("at", System.currentTimeMillis());
            o.put("profile", profile == null ? "" : profile);
            JSONArray next = new JSONArray();
            next.put(o);
            for (int i = 0; i < arr.length() && next.length() < DL_CAP; i++) {
                next.put(arr.getJSONObject(i));
            }
            File tmp = new File(ctx.getFilesDir(), DL_FILE + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(next.toString().getBytes(StandardCharsets.UTF_8));
            }
            File target = new File(ctx.getFilesDir(), DL_FILE);
            if (!tmp.renameTo(target)) {
                try (FileOutputStream out = new FileOutputStream(target)) {
                    out.write(next.toString().getBytes(StandardCharsets.UTF_8));
                }
                tmp.delete();
            }
        } catch (Exception ignored) {
        }
    }

    static JSONArray loadDownloads(Context ctx) {
        File f = new File(ctx.getFilesDir(), DL_FILE);
        if (!f.exists()) return new JSONArray();
        try (FileInputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) != -1) bos.write(chunk, 0, n);
            if (bos.size() == 0) return new JSONArray();
            return new JSONArray(new String(bos.toByteArray(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new JSONArray();
        }
    }
}
