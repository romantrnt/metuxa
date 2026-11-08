# metuxa 0.4.0 verification

Release preparation: 2026-11-08. Source and Android version are 0.4.0; Android and Linux update code are 5. Stream protocol remains 5. The previous 0.3.0 release remains the rollback reference. Its [verification record](VERIFICATION.md) is historical evidence, including its emulator tests, and is not a claim that those checks ran again for 0.4.0.

## Passed development checks

The display and appearance implementation is in commit `8904c1d`. The [display verification record](DISPLAY-VERIFICATION.md) contains the test setup and detailed outcomes:

- Host build and all eight Meson suites passed: protocol, input, encoder resize/audio, security, desktop controls, updates, appearance and appearance without GNOME schemas. The same suites passed in the existing offline Ubuntu 24.04 AppImage toolchain with GCC 13.3, gtkmm 4.10 and PipeWire 1.0.5. Both capture backends compile in a separate Mutter-enabled build.
- A separate development AppDir passed GTK tests and actual control-app startup with its bundled libraries in private Weston. These checks did not replace the released AppImage or validate the final 0.4.0 artifact.
- The JDK 17 Java/C++ transport, input-queue and pairing suite passed. Authenticated orientation tests cover virtual-only permission, repeated requests, held-input release, disconnect, reconnect dimensions, recovery and restart-required errors. Metadata-only HELLO closes before READY without media or input; the next normal connection receives fresh media keys.
- Native lifecycle and discovery ASan/UBSan checks passed, including synthetic orientation success, idempotence, rollback and fail-closed paths. Appearance ASan/UBSan checks also passed.
- Isolated Mutter 50.5 with PipeWire 1.6.9 completed three real 640×360/360×640 cycles. Actual DisplayConfig modes, normal image transform and captured-frame dimensions matched. The original monitor stayed unchanged; the virtual monitor retained its stream path and PipeWire node, then disappeared on session close. The test had no access to the user's desktop, runtime sockets, hardware devices or network.
- The host GNOME preference was read without changing it. Its `prefer-dark` setting and both portal reply forms are covered. Private-bus and Weston checks passed for live theme changes, explicit-theme-to-System transitions, portal restart, stale replies, destruction during pending reads, missing schemas, GTK fallback and High contrast cleanup, on host GTK 4.25 and the packaged runtime.
- Offline Android debug and unsigned release assembly passed. Debug and release lint each reported 0 errors and 17 retained warnings. All 21 native View/probe/shutdown tests passed: 15 View/state tests, three authenticated metadata-probe tests and three stream-shutdown tests. Core coverage passed with 614 input/discovery assertions, 209 monitor/window/orientation checks, 237 update/security checks and 51 media-lifecycle checks.
- Android View checks cover narrow layouts, 150% text, scrolling, form retention, ignored legacy image rotation, physical-source explanations, virtual orientation choices, panel hints, overlay opacity and persistent restart-required errors. The README screenshots are sanitized renders of actual Android Views and GTK widgets, not physical Quest screenshots. Final code review found no remaining blocking issue; this is not an independent security audit.

## Passed 0.4.0 release preparation

- A fresh 0.4.0 build in the Ubuntu 24.04 release environment passed all eight Meson suites.
- The freshly collected release AppDir passed bundled GTK System/explicit-theme checks and actual control-app startup in private Weston. Its closure contains 133 ELF objects with no unresolved libraries in the clean Ubuntu 24.04 baseline; the maximum required glibc symbol version is GLIBC_2.38. The supported host baseline remains glibc 2.39. Inventories identify 115 Ubuntu binary packages and 93 source packages. Pinned source-cache and rebuilt-runtime hashes were verified.
- Android 0.4.0 release assembly, release lint and core tests passed. Lint retained 0 errors and 17 warnings. Core checks passed with 614 input/discovery assertions, 209 monitor/window/orientation checks, 122 static checks, 237 update/security checks and 51 media-lifecycle checks. All 21 Android View/probe/shutdown tests passed with no failures or skips.
- The final arm64-v8a APK is non-debuggable `dev.metuxa.quest`, version 0.4.0/code 5, API 32 minimum/API 34 target. Its existing production signer, APK v3 signature and alignment were verified. The signed file is 1,254,595 bytes with SHA-256 `fc67628374cd0e00cfc1bf8648df16c6f300675cb766736271d15cebbf01ce05`. No new emulator upgrade or physical Quest test was run.

## Checks pending at source freeze

This record is frozen before the source archive and final AppImage are assembled. At that point, the checks below remain pending. Their final outcomes, artifact sizes/checksums and exact-commit CI links belong in the [0.4.0 release notes and assets](https://github.com/romantrnt/metuxa/releases/tag/v0.4.0). A published binary must not be treated as verified merely because this source-freeze record exists.

- Final AppImage assembly, artifact CLI validation, synthetic media integration, mascot bytes, desktop entry and deterministic assembly. The collected AppDir checks above do not establish the final packaged artifact result.
- Exact application source revision and source-input checksums; complete bundled dependency sources, patches, build records, license texts and their final inventories.
- Signed update manifest verified offline against the unchanged production public key and exact final APK/AppImage bytes, including the upgrade from 0.3.0/code 4 and the exact-current announcement.
- Remote commit/tag, CI for that exact commit, and complete uploaded assets with matching sizes and SHA-256 checksums before release promotion.

## Known limits

- No physical Quest or Horizon OS test was run. Initial outer-panel sizing remains a hint that Horizon can ignore or override. MediaCodec transitions, controller/hand/IME behavior, live pointer mapping after resize, and application-specific window reflow still need the [manual acceptance checks](MANUAL-TESTS.md).
- The real-compositor test uses an isolated direct Mutter session. It does not validate GNOME's actual portal picker or capture the user's desktop. The portal virtual-stream implementation supports the same negotiation mechanism, but its consent flow and live behavior remain separate checks.
- The real-compositor ASan/UBSan run finished mode changes without invalid-access or undefined-behavior findings, but LeakSanitizer failed with 3150 bytes in 34 allocations from PipeWire context/module initialization. A minimal program using only `pw_init`, context creation/destruction and `pw_deinit` reproduced exactly that report. This is not a clean leak check. The focused metuxa lifecycle, discovery and appearance sanitizer checks passed.
- No 0.3.0-to-0.4.0 on-device installation or in-app PackageInstaller acceptance is established here. Installer permission denial, cancellation, process death, insufficient storage and retained preferences need separate results. The 0.3.0 emulator record does not cover this upgrade.
- Hardware video encoding, live audio capture, physical-headset performance/comfort and Noda coexistence were not established by these isolated tests. A stalled hardware codec can still delay replacement while teardown is pending.
- A physical screen remains an exact mirror. Changing its mode through the host display settings during sharing requires a deliberate sharing restart; metuxa does not change it or restart sharing automatically.
- clang-format and clang-tidy are absent; `scripts/lint.sh` was not run. Android lint, compiler checks and the listed tests do not substitute for that missing check.

See [AppImage packaging](APPIMAGE.md), [signed updates](UPDATES.md) and [release procedure](RELEASING.md) for the artifact requirements.
