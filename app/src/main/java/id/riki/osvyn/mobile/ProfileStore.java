package id.riki.osvyn.mobile;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Profile list persisted as profiles.json in the app files dir.
 * Only MainActivity (main process) writes it. Profile processes only read it,
 * fresh from disk on every launch, so no cross-process SharedPreferences.
 */
public final class ProfileStore {

    public static final int MAX_PROFILES = 10;
    public static final String MUSE_URL = "https://muse.ai/";

    public static final class Profile {
        public final String id;
        public final String name;
        public final int slot;   // 1..10, owns one process + one WebView data dir
        public final int color;  // index into COLORS
        public long opened;      // unix ms of last open, 0 = never opened

        Profile(String id, String name, int slot, int color) {
            this.id = id;
            this.name = name;
            this.slot = slot;
            this.color = color;
            this.opened = 0;
        }

        Profile(String id, String name, int slot, int color, long opened) {
            this.id = id;
            this.name = name;
            this.slot = slot;
            this.color = color;
            this.opened = opened;
        }
    }

    /** Hub avatar colours, copied from the desktop Hub (AURA palette). */
    public static final int[] AURA = {
            0xFF2B6FF2, 0xFF7C5CD6, 0xFF0E9F8A, 0xFFE08A1E, 0xFFD6577A,
            0xFF5B7A99, 0xFF2B6FF2, 0xFF7C5CD6, 0xFF0E9F8A, 0xFFE08A1E
    };

    public static final int[] COLORS = {
            0xFF15B3DA, 0xFFE67E22, 0xFF2ECC71, 0xFFE74C3C, 0xFF9B59B6,
            0xFFF1C40F, 0xFF1ABC9C, 0xFFE91E63, 0xFF95A5A6, 0xFF5D8AA8
    };

    private static File file(Context ctx) {
        return new File(ctx.getFilesDir(), "profiles.json");
    }

    public static synchronized List<Profile> load(Context ctx) {
        List<Profile> out = new ArrayList<>();
        File f = file(ctx);
        if (!f.exists()) return out;
        try {
            byte[] buf = new byte[(int) f.length()];
            try (FileInputStream in = new FileInputStream(f)) {
                int read = in.read(buf);
                if (read <= 0) return out;
            }
            JSONArray arr = new JSONArray(new String(buf, StandardCharsets.UTF_8));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Profile(
                        o.getString("id"),
                        o.getString("name"),
                        o.getInt("slot"),
                        o.optInt("color", 0),
                        o.optLong("opened", 0)));
            }
        } catch (Exception ignored) {
            // Corrupt file: return what was parsed rather than crash.
        }
        return out;
    }

    public static synchronized void save(Context ctx, List<Profile> profiles) {
        try {
            JSONArray arr = new JSONArray();
            for (Profile p : profiles) {
                JSONObject o = new JSONObject();
                o.put("id", p.id);
                o.put("name", p.name);
                o.put("slot", p.slot);
                o.put("color", p.color);
                o.put("opened", p.opened);
                arr.put(o);
            }
            try (FileOutputStream out = new FileOutputStream(file(ctx))) {
                out.write(arr.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
    }

    /** Smallest free slot in 1..MAX_PROFILES, or -1 when full. */
    public static int freeSlot(List<Profile> profiles) {
        boolean[] used = new boolean[MAX_PROFILES + 1];
        for (Profile p : profiles) {
            if (p.slot >= 1 && p.slot <= MAX_PROFILES) used[p.slot] = true;
        }
        for (int s = 1; s <= MAX_PROFILES; s++) {
            if (!used[s]) return s;
        }
        return -1;
    }

    /** Marker written by a profile process after it wiped its own data. */
    public static File wipeMarker(Context ctx, int slot) {
        return new File(ctx.getFilesDir(), "wiped_slot_" + slot + ".done");
    }

    public static Class<?> activityClassForSlot(int slot) {
        switch (slot) {
            case 1: return Profile1Activity.class;
            case 2: return Profile2Activity.class;
            case 3: return Profile3Activity.class;
            case 4: return Profile4Activity.class;
            case 5: return Profile5Activity.class;
            case 6: return Profile6Activity.class;
            case 7: return Profile7Activity.class;
            case 8: return Profile8Activity.class;
            case 9: return Profile9Activity.class;
            case 10: return Profile10Activity.class;
            default: return Profile1Activity.class;
        }
    }
}
