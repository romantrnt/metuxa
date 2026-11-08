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
gradle --no-daemon assembleDebug lintDebug testCore
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

## Release packaging

The Linux release is an x86_64 AppImage built in a private Ubuntu 24.04 container, not a copy of this laptop's Arch/Garuda libraries. Its AppImage runtime is separately rebuilt from pinned official source in Alpine. It includes the control application, server, Tux icon, required library/plugin closure, full license texts and source inventories. It does not install a service or start sharing automatically.

See [APPIMAGE.md](APPIMAGE.md) for the complete build sequence, source collection, artifact tests and compatibility limits. The baseline is glibc 2.39 (Ubuntu 24.04); older LTS distributions are not supported by this build. Hardware drivers, desktop portal and PipeWire services remain host interfaces.

The release must include the exact metuxa source archive and dependency-source archive alongside the AppImage because the server uses a GPL-enabled FFmpeg/x264 configuration. The source MIT `LICENSE` remains unchanged; [THIRD-PARTY-NOTICES.md](../THIRD-PARTY-NOTICES.md) explains the combined-binary license.

Android signing/versioning and publication checks are documented in [RELEASING.md](RELEASING.md). `scripts/package-native-test.sh` remains a host-specific developer test-folder helper, not the public AppImage packager. The Nix/Flatpak recipes are alternative build recipes; this release pass does not certify rebuilt Nix or Flatpak packages.
