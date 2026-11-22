#!/usr/bin/env bash
# Manual APK build for Osvyn Mobile (no Gradle: the VM intercepts the
# Gradle daemon loopback socket, so this uses the SDK build-tools directly).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_HOME:-$HOME/workspace/tools/android-sdk}"
export JAVA_HOME="${JAVA_HOME:-$HOME/workspace/tools/jdk-17}"
export PATH="$JAVA_HOME/bin:$PATH"
BT="$SDK/build-tools/34.0.0"
AJAR="$SDK/platforms/android-34/android.jar"
SRC="$ROOT/app/src/main"
OUT="$ROOT/app/build/manual"
KEYSTORE="$ROOT/debug.keystore"

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "[1/6] aapt2 compile + link"
"$BT/aapt2" compile --dir "$SRC/res" -o "$OUT/compiled_res.zip"
"$BT/aapt2" link -o "$OUT/osvyn-unsigned.apk" \
    -I "$AJAR" \
    --manifest "$SRC/AndroidManifest.xml" \
    -R "$OUT/compiled_res.zip" \
    -A "$SRC/assets" \
    --java "$OUT/gen" \
    --min-sdk-version 28 \
    --target-sdk-version 34 \
    --version-code 15 \
    --version-name "1.6.1" \
    --auto-add-overlay

echo "[2/6] javac"
find "$SRC/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -cp "$AJAR" -d "$OUT/classes" @"$OUT/sources.txt"

echo "[3/6] d8 dex"
"$BT/d8" --lib "$AJAR" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

echo "[4/6] add dex to apk"
cp "$OUT/osvyn-unsigned.apk" "$OUT/osvyn-with-dex.apk"
(cd "$OUT/dex" && zip -q "$OUT/osvyn-with-dex.apk" classes.dex)

echo "[5/6] zipalign"
"$BT/zipalign" -pf 4 "$OUT/osvyn-with-dex.apk" "$OUT/osvyn-aligned.apk"

echo "[6/6] sign"
# UPDATE GUARANTEE (Riki, 2026-11-03): every release must install as an
# in-place update so existing profiles and sign-ins survive. That needs
# the SAME signing key forever. Never silently generate a new key: a new
# key would force an uninstall and wipe every profile. Restore from the
# backup copy instead, and fail loudly if both are gone.
if [ ! -f "$KEYSTORE" ]; then
    if [ -f "$ROOT/signing-backup/debug.keystore" ]; then
        cp "$ROOT/signing-backup/debug.keystore" "$KEYSTORE"
        echo "keystore restored from signing-backup"
    else
        echo "FATAL: signing keystore missing and no backup. Refusing to" >&2
        echo "generate a new key (it would wipe all installed profiles)." >&2
        exit 1
    fi
fi
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android \
    --key-pass pass:android --ks-key-alias androiddebugkey \
    --out "$ROOT/OsvynMobile-1.6.1.apk" "$OUT/osvyn-aligned.apk"

EXPECTED_CERT="8f902c542d23860078e8b9f34219faef58c0d34e6276da8a4e7da03e96d53f73"
ACTUAL_CERT=$("$JAVA_HOME/bin/keytool" -printcert -jarfile "$ROOT/OsvynMobile-1.6.1.apk" 2>/dev/null \
    | grep -i "SHA256:" | sed 's/^[^:]*SHA256://I' | tr -d ': \t' | tr 'A-F' 'a-f')
if [ "$ACTUAL_CERT" != "$EXPECTED_CERT" ]; then
    echo "FATAL: signer certificate mismatch ($ACTUAL_CERT)." >&2
    echo "This APK would NOT install as an update. Not shipping it." >&2
    exit 1
fi
echo "signer certificate OK (in-place update guaranteed)"
ls -lh "$ROOT/OsvynMobile-1.6.1.apk"
echo "BUILD_OK"
