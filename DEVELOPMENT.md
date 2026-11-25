# Osvyn Mobile

Standalone Android version of Osvyn, the multi-account manager for Muse.
Rebuilt in Yukii's VM on 2026-11-03 from the same product idea as the desktop
build (Osvyn 0.2.9, Tauri): many Muse accounts in one app, every account in
its own profile with a separate, persistent login session.

## How profiles stay separate on Android

The desktop build gives every profile its own WebView with its own data
folder. Android does not allow that inside one process, so Osvyn Mobile
gives every profile:

- its own Android process (`:profile1` to `:profile10`), and
- its own WebView data directory, set with
  `WebView.setDataDirectorySuffix("osvyn_profile_<slot>")` before any
  WebView is created in that process.

Cookies, local storage and cache are therefore fully separate per profile.
Profiles never share a Muse login.

## Changes in 1.3.0 (message notifications, Riki 2026-11-03)

- Injected watcher script in every profile page on muse.ai: it
  observes DOM changes and polls every 15s for unread signals (page
  title "(n)" prefix, aria-label or data-testid "unread" badges) and
  reports through a native bridge. When a new unread state appears
  while that profile is not on screen, the app posts a notification
  on the high-importance "Muse messages" channel (default sound,
  vibration pattern, heads-up), titled with the profile name. The
  same unread state never re-rings inside 45 seconds; opening the
  profile clears its notification.
- Tapping a notification opens that exact profile (per-slot
  PendingIntent with the profile extras, CLEAR_TOP).
- WatchService foreground service (dataSync) with a quiet persistent
  "Osvyn watcher active" notification keeps the app alive while
  watching; its text counts opened profiles.
- DeepSleep changed to make this possible: backgrounded profiles no
  longer unload to about:blank and their JavaScript is not paused;
  only the WebView renderer is waived. Pages stay live to hear
  messages and reopen instantly.
- Android 13+ notification permission is requested on Hub open, with
  a tappable hint in the Hub while it is denied.
- Honest limits: watching covers profiles that have been opened at
  least once and runs only while the app has not been force stopped;
  Muse exposes no official push channel to third-party apps. Which
  unread signal muse.ai actually emits is device-test territory: if
  notifications do not fire, the watcher selectors get tuned to the
  real signal from Riki's report.

## Changes in 1.2.0 (responsiveness and UX pass, Riki 2026-11-03)

- Every control now answers the touch instantly: pressed-state
  backgrounds on all buttons and profile bar tabs, plus a light haptic
  tick on tab switches, card opens, and button taps (shared Ui helper).
- Profile cards press in slightly under the finger and the whole card
  opens the profile.
- Faster profile switching: WebViews render on the hardware layer, and
  the visible profile owns the WebView renderer while hidden profiles
  waive theirs (renderer priority policy), so the profile in front
  gets full GPU and CPU.
- Splash hold shortened from 1.9s to about 1.15s (tap still skips).

## Changes in 1.1.3

- Muse launcher icon made to fit: Riki found the M squiggle too big,
  so the vector mark is scaled down from 72% to 54% of the icon frame
  and re-centered, and the pre-Android-8 fallback PNG is padded the
  same way (mark at 54% on white).

## Update guarantee (standing rule, Riki 2026-11-03)

Every release installs as an in-place update: profiles and sign-ins
survive. Invariants that make this true, checked by the build script:

- Package name never changes: id.riki.osvyn.mobile.
- Signer certificate never changes: SHA-256
  8f902c542d23860078e8b9f34219faef58c0d34e6276da8a4e7da03e96d53f73.
  The keystore lives in this folder with a backup in signing-backup/;
  build-manual.sh restores from the backup if the main copy is lost and
  refuses to generate a new key (a new key would force an uninstall
  and wipe all profiles). After signing, the build verifies the
  certificate fingerprint and fails loudly on mismatch.
- versionCode only ever increases.
- On-device data layout is frozen: profiles.json plus per-slot WebView
  data directories (suffix osvyn_profile_N). Any future format change
  must migrate data, never reset it.

Verified from the APK files: 1.0.0 and 1.1.2 carry the identical
signer certificate. User-side rule: install new APKs over the old app,
never uninstall first (Android deletes app data on uninstall).

## Changes in 1.1.2 (Riki's feedback on 1.1.1, 2026-11-03)

- The New profile and Delete profile dialogs are now custom Hub-style
  panels (white card, Poppins, rounded input, blue primary button)
  instead of the stock Android AlertDialog Riki called "classic".
  Profile bar tabs now use Poppins SemiBold and the active tab has a
  raised shadow.
- The Hub pill in the profile bar now goes straight to the Hub:
  MainActivity is brought to the front with CLEAR_TOP, and profile
  switching uses the same flag so the stack stays flat
  (Main, then one profile) instead of piling profiles up.
- App icon is now the official Muse logo: the blue-gradient squiggle
  from muse.ai's own favicon asset (app-squiggle.svg), drawn as a
  vector adaptive icon on white, with the official favicon PNG as the
  pre-Android-8 fallback. Source files kept in brand/.

## Changes in 1.1.1 (bug fixes reported by Riki, 2026-11-03)

- Downloads from Muse fixed with a port of the desktop download
  bridge. Desktop evidence (29 Oct): muse.ai attachment downloads are
  blob: URLs and the native download handler never finishes them, on
  WebView2 or Android WebView. The injected script now intercepts blob
  download clicks (real and programmatic), reads the blob in the page,
  and passes it to the app in base64 chunks; the app writes the file to
  the phone's Downloads folder (MediaStore) and toasts "Saved to ...".
  HTTP downloads still use DownloadManager and now report failures.
- Artifacts and connectors: the WebView now presents a desktop Chrome
  user agent with a wide viewport and pinch zoom, like the desktop app,
  instead of the phone layout that left artifact panels blank. Popup
  windows (connector OAuth) are now real overlay windows that keep
  window.opener and close via window.close, instead of loading the
  popup URL into the main view; mixed content mode is set to
  compatibility and third-party cookies are on for popups too.
- Device verification of these three flows (download an attachment,
  open an artifact, connect one connector) is Riki's test pass.

## Changes in 1.1.0

The whole UI was reworked to match the desktop Osvyn (per Riki's feedback
that 1.0.0 worked smoothly but looked ugly):

- Opening splash ported from the desktop splash.html: white screen,
  "Osvyn" in Poppins Bold with the cyan dot, scale/translate entrance,
  tap to skip.
- Home is now a port of the desktop Hub page: light #FBFBFC surface,
  wordmark with cyan dot, stats strip, white profile cards with AURA
  avatars, presence dots and status chips (Asleep / Not signed in),
  blue primary button, ghost Open and light-danger Delete buttons,
  staggered card entrance.
- Creating a profile shows a port of splash_shift.html: smaller wordmark,
  "Preparing new profile" with animated dots and the cyan progress line.
- The in-profile top bar is now the desktop profile bar: dark #101014
  row of pill tabs, active tab inverted to #E8E6DF, cyan dot for the
  awake profile and grey dots for sleeping ones, + and reload pills,
  thin cyan progress line under the bar.
- Poppins Bold and SemiBold are bundled in assets/fonts.
- Same signing key as 1.0.0, so it installs as an update and keeps all
  profiles and logins.

## Features in 1.0.0

- Add up to 10 profiles, each with a name and a colour dot
- Sign in to a different Muse account inside each profile (muse.ai)
- QuickSwitch chip bar inside every profile to jump between profiles
- DeepSleep: a profile idle in the background for 3 minutes releases its
  page to free RAM; its login session stays saved and reloads on return
- File upload chooser and downloads (saved to the phone Downloads folder)
- Popup windows from Muse (for example sign-in flows) handled in-app
- Delete a profile: the profile's own process wipes its cookies and
  storage first, and only then is the slot freed, so a new profile can
  never inherit a deleted profile's login

## Not in 1.0.0

- No licence activation yet. This build is for Riki's own use.
- No Hub split view and no profile export/import (Profile Vault) yet.
- Android only. There is no iOS build.

## Building

Needs JDK 17 and Android SDK platform 34 / build-tools 34.

In Yukii's VM, use the manual build script. It drives the SDK build-tools
directly (aapt2, javac, d8, zipalign, apksigner) because the VM intercepts
the Gradle daemon's loopback socket, which makes Gradle builds fail there:

```text
export JAVA_HOME=~/workspace/tools/jdk-17
export ANDROID_HOME=~/workspace/tools/android-sdk
./build-manual.sh         # writes OsvynMobile-1.0.0.apk in this folder
```

On a normal machine, a standard Gradle build also works:

```text
gradle assembleDebug
```
