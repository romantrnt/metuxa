# Local builds

## Native Linux

Prerequisites: C++20, Meson, Ninja, pkg-config, FFmpeg libavcodec/libavutil/libswscale/libswresample, OpenSSL, PipeWire development files, sdbus-c++1.5 development files, GTK4/gtkmm4 and json-glib development files.

```sh
meson setup build -Dportal=enabled -Dmutter=disabled -Dbuildtype=debugoptimized
meson compile -C build
meson test -C build --print-errorlogs
```

Do not use sdbus-c++2 without adapting the portal API. Current Arch uses an FFmpeg API newer than the original source; the audio encoder contains a version-conditional supported-format query. The local `build-native` uses a private `.deps/sdbus` copy made from the previously verified local sdbus-c++1.5 build; it does not modify system libraries.

`metuxa-server --validate` and `--help` never start capture/network/input. Real server Start requires either a terminal or `--status-fd N`, a writable private pipe consumed by the UI. The status stream contains pairing details; do not record it in ordinary diagnostics.

## Android

Use JDK17 and Gradle8.7 with Android SDK platform/build tools configured by `ANDROID_HOME`. The prepared laptop toolchains can be reused without installing another SDK or changing system Java.

```sh
cd client/quest
gradle --no-daemon assembleDebug lintDebug testDebugUnitTest
```

The metuxa application ID is separate from legacy MetaShare, so a test install does not overwrite the working old app. Debug signatures are not public release signatures. Never uninstall an existing app to work around a signature mismatch without reviewing the data loss.

## Tests and isolation

Meson tests cover packet parsing, input lifecycle and the control/transport helpers. Additional security and cross-language tests live under `tests/` and client test directories. Sanitizer builds are recommended:

```sh
meson setup build-sanitize -Dportal=disabled -Dmutter=disabled -Db_sanitize=address,undefined
meson compile -C build-sanitize
meson test -C build-sanitize --print-errorlogs
```

Synthetic server integration must use a separate network namespace, no session bus/display/PipeWire access, software encoding, and test-pattern/test-tone sources. GUI smoke tests use a private headless compositor/session. Never mistake these tests for real portal, GPU or Quest validation.

## Packaging

The local test bundle contains the server, control app, required local sdbus library, launcher, APK, source snapshot, checksums and verification report. It neither installs nor starts automatically. AppImage generation and a release APK/repository publication are deliberately deferred until the live test and explicit release instruction. The Nix/Flatpak recipes have been adapted to metuxa identities and pinned source dependencies, but were not rebuilt in this native test pass. They are not proof of a tested public package.
