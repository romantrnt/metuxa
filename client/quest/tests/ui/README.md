# Opt-in native Android UI checks

This suite renders the **real Android Views** using Robolectric 4.14.1, Android
API 34 and native graphics. It is not a screenshot from Quest/Horizon OS and
does not verify MediaCodec, physical input, the IME, audio or live streaming.
It never installs an APK, uses adb, launches a stream or discovers LAN hosts.
MainActivity's initial discovery is suppressed before its real resume callback.

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

Twelve test cases cover:

- Launcher layouts at 1200 × 1000, 480 × 1000, and 360 × 800 with 150% font.
- Wide → narrow → wide resizing, minimum touch targets, label height and gaps.
- Full remote, Keyboard only and View only presets and persisted effective state.
- Dark/light/system settings, day/night changes and form retention through recreation.
- Per-monitor rotation persistence and inverse Android touch/hover at four angles on the TextureView renderer. These tests do not decode video or prove rotated pixel output.
- Overlay timing, pinned Controls handle, local focus release, return to remote typing and the fixed More menu.
- Production StreamSession.stop with blocking test sockets: no close on the caller/UI thread, duplicate shutdown, and isolation from replacement sockets.
- Forget cancellation, pointer-device information, diagnostics Reset/Close.
- Toolbar callbacks, disabled keyboard state and visible fixed Disconnect at
  800 px and 360 px/150% font, without a connected media session. Native mouse cursor positions are stubbed by Robolectric, so the hover matrix test uses stylus-source coordinates.

PNG renders are written to `client/quest/app/build/ui-qa/screenshots/`; JUnit
reports are in the standard `app/build/reports/tests/testDebugUnitTest/` folder.
Viewport renders show the real ScrollView. Full-page renders draw its measured
child on the same background. Dialogs use explicit bounded dimensions; toolbar
renders isolate the real component. All test addresses are reserved documentation
addresses and pairing fields stay empty. Generated renders are not checked in.

Visually inspect the output when changing layout or styling. Passing this suite
supplements, and does not replace, the [headset acceptance checklist](../../../../docs/MANUAL-TESTS.md).
