# Opt-in native Android UI checks

This suite renders native Android Views using Robolectric 4.14.1, Android
API 34 and native graphics. It runs locally without installing an APK, using
adb, launching a stream or discovering LAN hosts. MainActivity's initial
discovery is suppressed before its resume callback.

The optional Gradle init script adds JUnit/Robolectric **test dependencies only
for this invocation**. Normal builds, production runtime dependencies and the
independent offline `testCore` suite remain unchanged.

## Run

Use an existing JDK 17, Gradle 8.7 and Android SDK platform 34. Set `JAVA_HOME`,
`ANDROID_HOME`, and optionally `GRADLE_USER_HOME` to your existing toolchain/cache.
Set `GRADLE` to the Gradle executable if it is not on `PATH`.

```sh
# First run only: fetch test packages from Maven Central and Google.
client/quest/tests/ui/run.sh --online

# Later runs: both Gradle and Robolectric dependency resolution are offline.
client/quest/tests/ui/run.sh
```

Robolectric caches its instrumented Android framework jars under `~/.m2`.
Offline runs stage hard links to those cached jars in the ignored app build
folder (copying only if the cache is on another filesystem). A missing cache
produces an actionable error rather than enabling network access silently.
The initial download can use several hundred megabytes; no emulator or system
image is installed.

## Coverage and output

The test cases cover:

- Stable application, Activity and launcher-intent labels, with the packaged
  version beside the launcher brand and in Settings after recreation.
- APK archive rejection for old/unrelated IDs, mismatched versions and missing signers.
- Launcher and monitor overflow actions, repeated menu/About requests, Close/Back,
  About artwork, working GitHub/credits intents and scrollable links at large text.
- Launcher layouts at 1200 × 1000, 480 × 1000, and 360 × 800 with 150% font.
- Wide → narrow → wide resizing, minimum touch targets, label height and gaps.
- The last launcher section and last settings control remain reachable by
  scrolling, including the 360dp/150% font layout.
- Full remote, Keyboard only and View only presets, native switches, persisted
  effective state, and expandable advanced sections.
- Dark/light/system settings, day/night changes and form retention through recreation.
- Legacy image-rotation preferences are ignored by a real StreamActivity;
  authenticated dimensions request portrait/landscape without tilting the View.
- Physical-source explanation, virtual orientation dialog cancellation, per-PC
  size persistence, bounded launch hints and native settings-row menus.
- The renderer's inverse Android touch/hover transform at four angles.
  Image rotation is not exposed in the app.
- Overlay timing, pinned Controls handle, local focus release, return to remote typing and the fixed three-dot menu.
- Metadata probe parsing, AUTH-only output, background close-before-callback,
  cancellation, expiry fallback, duplicate starts, stale generation suppression,
  and returning to an active stream without a probe. Probe tests use fake TLS
  sockets and never connect to a server.
- Persistent display-rollback failure guidance and stopped reconnect state.
- Rounded toolbar background and configured opacity in the actual overlay.
- Production StreamSession.stop with blocking test sockets: no close on the caller/UI thread, duplicate shutdown, and isolation from replacement sockets.
- Forget cancellation, pointer-device information, diagnostics Reset/Close.
- Toolbar callbacks, disabled keyboard state and visible fixed Disconnect at
  800 px and 360 px/150% font, without a connected media session. The stream Activity orientation test is
  created without attaching its TextureView, so it cannot start a media session. Native mouse cursor positions are stubbed by Robolectric, so the hover matrix test uses stylus-source coordinates.

PNG renders are written to `client/quest/app/build/ui-qa/screenshots/`; JUnit
reports are in the standard `app/build/reports/tests/testDebugUnitTest/` folder.
Viewport renders show the real ScrollView. Full-page renders draw its measured
child on the same background. Dialogs use explicit bounded dimensions; toolbar
renders isolate the real component. All test addresses are reserved documentation
addresses and pairing fields stay empty. Generated renders are not checked in.

Visually inspect the output when changing layout or styling. For device tests,
follow the [headset acceptance checklist](../../../../docs/MANUAL-TESTS.md).
