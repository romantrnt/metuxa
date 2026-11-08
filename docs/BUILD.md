# Build and test

Run commands from the repository root unless a step says otherwise. Release binaries are an x86_64 Linux AppImage and an ARM64 Android APK.

## Native Linux

Install a C++20 compiler, Meson, Ninja, pkg-config and development packages for OpenSSL, libcurl >= 7.85, FFmpeg (libavcodec, libavutil, libswscale, libswresample), PipeWire, sdbus-c++ 1.5, gtkmm >= 4.10 / GTK4, and json-glib.

The portal backend uses the sdbus-c++ 1.x API. Version 2 needs source changes. The audio encoder supports both the FFmpeg 7 API and newer supported-format queries.

```sh
meson setup build -Dportal=enabled -Dmutter=disabled -Dbuildtype=debugoptimized
meson compile -C build
meson test -C build --print-errorlogs
PATH="$PWD/build/src/streamer:$PATH" ./build/client/gtk/metuxa-control
```

The last command opens the control app without starting sharing. It finds `metuxa-server` beside the control executable or on `PATH`. The Mutter virtual-display backend is optional; enable it with `-Dmutter=enabled` only for a compatible GNOME session.

`metuxa-server --validate` and `--help` do not start capture, networking or input. Starting the server requires a terminal or `--status-fd N`, a writable private pipe consumed by the UI. The status stream includes pairing credentials; do not record it in ordinary logs.

## Android

Use JDK 17, Gradle 8.7, Android SDK platform 34 and build-tools 34.0.0. Set `JAVA_HOME` and `ANDROID_HOME` to those installations. The project uses Android Gradle Plugin 8.5.0; Gradle needs network access on the first build unless dependencies are already cached. No NDK, CMake or OpenXR SDK is needed.

```sh
cd client/quest
gradle --no-daemon :app:assembleDebug :app:lintDebug :app:testCore
```

The debug APK is `client/quest/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`, relative to the repository root. It uses the `dev.metuxa.quest` package ID and a local debug signature. It cannot update a release-signed install. No build command installs or launches the APK.

Use `:app:assembleRelease :app:lintRelease :app:testCore` for an unsigned release build. Signing and safe upgrades are covered in [Releasing](RELEASING.md).

## Tests

Meson runs the native protocol, input, encoder, security, desktop-control and update suites. Standalone tests cover Java/C++ transport interoperability, pairing and lifecycle regressions. With JDK 17 and the native dependencies available:

```sh
sh tests/security/run.sh
bash tests/run-review-lifecycle.sh
bash client/quest/tests/run-core-tests.sh
```

Native sanitizer checks:

```sh
meson setup build-sanitize -Dportal=disabled -Dmutter=disabled -Db_sanitize=address,undefined
meson compile -C build-sanitize
meson test -C build-sanitize --print-errorlogs
```

[GTK UI tests](../client/gtk/README.md#build-and-tests) require a private headless Weston and D-Bus session. [Android View tests](../client/quest/tests/ui/README.md) use Robolectric and need no emulator or headset. `scripts/lint.sh` requires clang-format, clang-tidy and dependencies for both native capture backends.

Synthetic streaming tests use a separate network namespace, software encoding, test-pattern/test-tone sources, and no access to the user's display, session bus or PipeWire devices. They do not establish real portal, GPU or Quest behavior. Current results are in [Verification](VERIFICATION.md); physical-device checks are in [Manual tests](MANUAL-TESTS.md).

## Release packaging

The [AppImage recipe](APPIMAGE.md) builds with pinned Ubuntu 24.04 dependencies and a separately rebuilt Alpine-based runtime. The package requires host glibc 2.39 or newer; GPU drivers, the desktop portal and PipeWire services come from the host. It does not install a service or start sharing automatically.

Distribute both the exact metuxa source archive and dependency-source archive alongside the AppImage. The server links a GPL-enabled FFmpeg/x264 configuration. The source MIT license is unchanged; [Third-party notices](../THIRD-PARTY-NOTICES.md) explain the combined-binary license and required source materials.

See [Releasing](RELEASING.md) for APK signing, checksums, signed updates and publication checks.
