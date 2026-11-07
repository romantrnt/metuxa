# Historical MetaShare build record (before metuxa)

This file records the original rollback build only. Current metuxa instructions and results are in docs/BUILD.md and docs/VERIFICATION.md.

# Local build, 2026-11-07

## Provenance

- Repository: https://github.com/makemake-kbo/metashare
- Both client and server source baseline: ed13beb32a705c268a9cfd5ac3c055c9e4ada433 (2026-09-06).
- These are local test builds, not maintainer-signed release artifacts.
- Android Java/source/build configuration and wire protocol are unchanged.

## Build-recipe changes

- CLI Flatpak runtime and SDK upgraded from end-of-life Freedesktop 24.08 to supported 26.08.
- GUI manifest remains on GNOME 50; the delivered server is CLI-only. The shared codec recipe also applies to the GUI manifest, but the GUI was not built.
- FFmpeg upgraded from n7.1 to n7.1.5; enabled libopus required by AudioEncoder, plus NVENC via nv-codec-headers n12.2.72.0. VAAPI and libx264 remain available.
- x264/FFmpeg parallelism respects FLATPAK_BUILDER_N_JOBS, default 2; this build used 4.

## Toolchains and resolved dependencies

- Isolated Arch JDK 17.0.21.0.3, Gradle 8.7, Android Gradle Plugin 8.5.0.
- Android platform 34 and build-tools 34.0.0; AGP auto-installed platform-tools 37.0.1. No NDK/CMake needed for the pure-Java client.
- Android SDK license accepted with user authorization: https://developer.android.com/studio/terms
- Arch JDK and flatpak-builder packages verified with the Arch keyring; Gradle SHA256 and Android commandline-tools checksum checked against official metadata.
- System Java and system packages were not changed. Official tools were extracted into the user-local build workspace; Flatpak runtime/SDK were installed in the user installation.
- flatpak-builder 1.4.12; Freedesktop SDK 26.08.4, commit a1b744b072d9f55d061e35382547ae80dad8d92abecc1da7dc1818de2daabfd3.
- FFmpeg n7.1.5 -> 3a0867c2bfda4a4d4309ca1a8cbdc6175e67f587.
- nv-codec-headers n12.2.72.0 -> 157becbf51c8b813425572b75c06c370bd43d8fd.
- sdbus-c++ v1.5.0 -> 30d9f1d46258c2be4ce29b7f7f168ecdf6d4f328.
- x264 stable resolved to b35605ace3ddf7c1a5d67a2eb553f034aef41d55. The upstream recipe still tracks the stable branch; a future fresh build may resolve differently.

## Validation results

- Android assembleDebug: PASS. Signature v2 verification and zipalign: PASS.
- Android lintDebug: FAIL (2 errors, 19 warnings), preserved without suppression. One error is Play target-SDK policy for target32; the other flags setSessionId(AUDIO_SESSION_ID_GENERATE), even though the official API explicitly permits that constant. See the complete lint report.
- Android testDebugUnitTest: NO-SOURCE, not a passing unit-test suite.
- Flatpak build/export/bundle: PASS. App commit 3dc005a254bd83488786bdbfee797deaee064dc2a8f1f3ace727397fc993500c.
- Final runtime linking: PASS, no missing libraries. libavcodec61.19.101.
- Codec registration probe inside Platform26.08: libx264, libopus, hevc_nvenc, h264_nvenc, hevc_vaapi, h264_vaapi all present. It did not initialize hardware encoders.
- Isolated C++ protocol smoke test with AddressSanitizer and UndefinedBehaviorSanitizer: PASS. Tests covered RTP headers, H264/H265 fragmentation and reconstruction, sequence/marker/timestamps, empty/malformed access units, Opus packetization, retransmit wrap eviction, and signaling round-trips. No network or capture involved. Tests are supplied in the delivery verification directory.
- Server compiler emitted upstream warnings, retained in server-build.log; build succeeded. The wrapper hit zsh's read-only status variable only after successful export; the bundle was separately produced and verified.

## Not yet tested or performed

No application installation, headset launch, desktop capture, microphone/system-audio capture, remote input, streaming, or Noda coexistence test. No real GPU encoding test. No claim of end-to-end compatibility.
The server has no authentication or transport encryption and binds LAN ports; use only a trusted private LAN without port forwarding. Start the first real capture with --audio none --no-input.
The current application mirrors monitors/virtual monitors, not independent existing GNOME application windows.

## Rebuild

Workspace: /home/khugardin/.cache/metashare-build-20261107
The original Flatpak manifests can be built with flatpak-builder --user --jobs=4 and the installed SDK26.08; use the existing state/repo paths to reuse compiled dependencies. Do not use --install until application installation is approved.
For Android, use the workspace JDK17, Gradle8.7, and android-sdk rather than the host JDK27 or the upstream script that additionally downloads unused NDK/CMake.
