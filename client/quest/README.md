# metuxa for Quest

A native, resizable Android 2D application for Quest/Horizon OS. The launcher
opens first. Discovery suggests PCs; it never chooses or trusts one for you.
The separate package `dev.metuxa.quest` can coexist with the original
`com.metashare.client` installation. Source namespace remains
`com.metashare.client` to preserve continuity with MetaShare.

## Connect

1. Start the metuxa desktop server. Choose the monitors, audio and remote-input
   permissions there. Host capture permission is still controlled by the host.
2. Open **metuxa** in Quest. Choose **Find nearby PCs**, a saved PC, or enter a
   hostname/IP address and the base port (normally **7878**).
3. Compare and enter the server's **full SHA-256 fingerprint** and **32-character
   pairing token**. Discovery results are unauthenticated hints, not verified
   identities. A new server run uses new pairing details.
4. Choose **Connect**. No connection happens merely because a PC is discovered.
   Automatic connection is opt-in and is tied to one specific saved PC.
5. Use **Settings** to return to the launcher, or **Disconnect all** to stop all
   metuxa panels and release remote input. The toolbar remains reachable even
   when remote pointer input is blocked.

Up to eight PCs can be saved. Tokens are encrypted using an AndroidKeyStore
AES-GCM key, bound to their endpoint. Pairing data is excluded from backups and
Android device transfers. Forget removes the saved pairing and auto-connect
preference; it does not silently disconnect an existing session.

## Pointer and hand tracking

The setting applies immediately to **all current and future metuxa windows**:

- **Full pointer input** forwards pointer movement, clicks and scroll.
- **Block all remote pointer input** blocks every pointer source inside remote
  content, while retaining remote keyboard input and local app controls.
- **Selected physical devices (experimental)** allows only the Android pointer
  device descriptors you select. Virtual or unknown devices and an empty
  allowlist are blocked. Replugging can change a device's identity.

Quest Home may expose hands and controllers as identical Android MotionEvents
or as the same input device. The app **cannot promise hand-only filtering**.
Blocking all remote pointer input is the dependable option for preventing hands
from manipulating the PC; it does not turn off hand tracking elsewhere in Home.
Device selection is not a security boundary or proof of a physical device's origin.

Switching pointer modes releases held pointer buttons. Cancellation, loss of
window focus, pause, disconnect and destroyed surfaces clean up held input.
Every pointer, hardware-key and IME path checks the resumed/window-focus gate;
late events after leaving a panel are not forwarded. A fresh contact or neutral
pointer state is required after a policy/focus change.

## Keyboard, windows and sound

- **Keyboard** opens the Android IME. Hardware navigation, modifier keys and
  shortcuts are supported. IME composition is not sent repeatedly as partial text.
- Pasted text is paced at up to 160 characters/second. Later keys, including
  Enter, wait behind it. At most 4096 pending operations are accepted; an
  oversized commit is rejected visibly instead of silently truncated.
- Keep the panel focused until **Text sent**. Losing focus or disconnecting
  cancels unsent text with a visible message; some text may already have arrived.
- The main panel opens up to three available monitors. All panels use the same
  selected endpoint, fingerprint and pairing session. Stale/restored panels
  cannot independently discover or connect to another PC.
- Only monitor 1 can play audio, and its setting can disable audio entirely.
  Other panels never create a second audio playback owner.
- A destroyed rendering surface stops its media session. A new surface starts a
  new stream to the same selected PC. Old callbacks cannot mutate the replacement.

## Opt-in input diagnostics

Collection starts **off** for every app process. The inspection view shows only:
source, tool type, numeric device ID, action and buttons, plus a bounded mapping
of current device name/vendor/product/descriptor/virtual/external metadata.
It never records coordinates, keyboard events, typed text, screenshots, pairing
secrets or remote content.

The rolling buffer holds at most 80 samples at 10 samples/second, in memory only.
The device mapping is capped at 16 devices. **Reset** erases collected samples;
disabling collection clears them too. No file upload, telemetry or log export
occurs. Metadata can help compare hands/controllers/mice on a particular Quest
runtime, but cannot establish a reliable hand/controller distinction on its own.

## Protocol and architecture

- Discovery: `METUXAD1`, protocol 5, UDP **7877**.
- Control: pinned TLS on **7878 + monitor index**, pairing authentication before
  the stream description. No cleartext or legacy-v4 fallback.
- Media: H.265/H.264 video and Opus audio transported as authenticated encrypted
  UDP packets. Android framework `MediaCodec`, `AudioTrack`, and `SurfaceView`
  decode and render without an OpenXR or third-party media dependency.
- The source-derived decoder keeps dependency-preserving queues and keyframe
  recovery rather than blocking the RTP receiver on decoder availability.
- `MainActivity`: launcher, pairing and settings.
- `ConnectionCoordinator`: one selected connection and global pointer policy.
- `BaseStreamActivity` / `StreamActivity` / `MonitorActivity`: panel lifecycle.
- `InputLifecycle`, `PointerPolicy`, `InputState`, `KeyboardQueue`: isolated
  state machines covered by executable Java tests.
- `StreamSession` / `SecureTransport` / `RtpReceiver`: authenticated transport.

## Build and verification

Requires an existing JDK 17, Gradle 8.7, Android SDK platform/build-tools 34, and
cached Android Gradle Plugin 8.5.0. No NDK or CMake is needed.

```sh
cd client/quest
JAVA_HOME=/path/to/jdk17 ANDROID_HOME=/path/to/android-sdk \
  GRADLE_USER_HOME=/path/to/existing-gradle-cache \
  /path/to/gradle-8.7/bin/gradle --offline --no-daemon \
  :app:assembleDebug :app:lintDebug :app:testCore
```

The APK is `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`. This is a
locally debug-signed test build, not a production release signature.

The standalone tests require only JDK 17 and Python 3, no network or Android
runtime:

```sh
JAVA_HOME=/path/to/jdk17 bash tests/run-core-tests.sh
```

See `VALIDATION.md` for the verified checks and remaining on-device test scope.
No build command above installs the APK, launches an app, captures a desktop,
or injects input into the user's session. Installation and end-to-end testing
must be performed separately by the user.
