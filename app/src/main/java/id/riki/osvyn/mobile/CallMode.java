package id.riki.osvyn.mobile;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Call Mode marker (1.4.4). One shared file in the app's files dir,
 * visible to every profile process of this app (the dir is per
 * package, not per process). While a profile is in a call its page
 * bridge rewrites the marker with a fresh timestamp every few
 * seconds; other profiles read it to know they should freeze. A
 * marker older than FRESH_MS is ignored, so a caller that dies
 * mid-call unfreezes everyone on its own.
 *
 * File format: line 1 = owning profile id, line 2 = write time in
 * epoch milliseconds.
 */
final class CallMode {

    private static final String FILE_NAME = "call_active.txt";
    private static final long FRESH_MS = 15_000L;

    private CallMode() {}

    static void setActive(Context ctx, String profileId, boolean active) {
        if (profileId == null || profileId.isEmpty()) return;
        try {
            File f = new File(ctx.getFilesDir(), FILE_NAME);
            if (active) {
                String body = profileId + "\n" + System.currentTimeMillis();
                File tmp = new File(ctx.getFilesDir(), FILE_NAME + ".tmp");
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                }
                if (!tmp.renameTo(f)) {
                    try (FileOutputStream out = new FileOutputStream(f)) {
                        out.write(body.getBytes(StandardCharsets.UTF_8));
                    }
                }
            } else if (profileId.equals(readOwner(ctx))) {
                // Only the owner clears its own marker.
                f.delete();
            }
        } catch (Exception ignored) {
        }
    }

    /** True when a fresh marker exists and belongs to another profile. */
    static boolean freshOwnedByOther(Context ctx, String myProfileId) {
        try {
            File f = new File(ctx.getFilesDir(), FILE_NAME);
            if (!f.exists()) return false;
            String body;
            try (FileInputStream in = new FileInputStream(f)) {
                byte[] buf = new byte[(int) Math.min(f.length(), 256)];
                int n = in.read(buf);
                if (n <= 0) return false;
                body = new String(buf, 0, n, StandardCharsets.UTF_8);
            }
            String[] parts = body.split("\n");
            if (parts.length < 2) return false;
            String owner = parts[0].trim();
            long ts = Long.parseLong(parts[1].trim());
            if (owner.isEmpty() || owner.equals(myProfileId)) return false;
            return System.currentTimeMillis() - ts < FRESH_MS;
        } catch (Exception e) {
            return false;
        }
    }

    private static String readOwner(Context ctx) {
        try {
            File f = new File(ctx.getFilesDir(), FILE_NAME);
            if (!f.exists()) return "";
            try (FileInputStream in = new FileInputStream(f)) {
                byte[] buf = new byte[(int) Math.min(f.length(), 256)];
                int n = in.read(buf);
                if (n <= 0) return "";
                String body = new String(buf, 0, n, StandardCharsets.UTF_8);
                int nl = body.indexOf('\n');
                return (nl >= 0 ? body.substring(0, nl) : body).trim();
            }
        } catch (Exception e) {
            return "";
        }
    }
}
