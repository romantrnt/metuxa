# metuxa 0.2.0 verification

Release preparation: **2026-11-08**. The maintainer accepted the prior local APK/server build before authorizing publication. This record separates automated checks from device acceptance; the final AppImage and release-signed APK were not installed onto the user's live setup by the release process.

## Passed on the final application sources

- **Native Linux:** host build and all five Meson suites (protocol, input state, encoder resize/audio, security and control/process supervision). The release packaging build also passed these five suites on its Ubuntu 24.04 baseline.
- **Second code review:** transport, authentication, input/lifecycle and packaging review. An untrusted-discovery allocation bound was fixed and re-reviewed. See [REVIEW-0.2.0.md](REVIEW-0.2.0.md) for findings and unresolved device-reliability risks. This is not an external professional security audit.
- **Security/interoperability:** C++ negative control/media tests, Java/OpenSSL pinned TLS, wrong fingerprint/token rejection, AES-GCM wire equivalence, invalid/replayed/wrong-source feedback rejection, input revocation and a sustained heartbeat session. Normal and ASan/UBSan runs passed. SessionInputQueue concurrency tests passed 37 assertions per run.
- **Sanitizers:** protocol/input and lifecycle/mocked-discovery tests passed with ASan/UBSan and leak detection. These tests do not request live capture or inject desktop input.
- **GTK interactions:** private headless compositor and private D-Bus, synthetic server only. Startup, sources, presets, permission/capability states, pairing privacy, live input controls, close cancellation and orderly shutdown passed. Normal and compact/dark layouts were visually inspected.
- **Android release:** JDK 17, Gradle 8.7, Android platform/build-tools 34. Final `assembleRelease`, `lintRelease` and `testCore` passed. Lint retains 0 errors and 10 warnings; they include Quest-only ABI, the fingerprint-pinned trust manager and untranslated UI strings.
- **Java regressions:** 614 assertions for endpoints, discovery budgets, all action-gate combinations, pointer ownership, source isolation, keyboard/IME queues and lifecycle/diagnostic bounds. Static architecture checks passed.
- **Graphite theme:** 122 offline checks; minimum enabled text contrast 5.43:1 for the checked palette combinations. Controls, dialogs, selection, focus and disabled states use the neutral palette.
- **Actual Android View rendering:** four opt-in Robolectric/native-graphics tests passed. Views were inspected at 1200 px, 480 px and 360 px with 150% font scaling, plus wide/narrow resizing, presets, dialog Cancel/Reset/Close and toolbar controls at 800/360 px. Visual inspection found and fixed missing adaptive button gaps. These are real View renders, not screenshots from Horizon OS. See [UI test instructions](../client/quest/tests/ui/README.md).
- **APK identity:** non-debuggable `dev.metuxa.quest`, version 0.2.0 / code 3, API 32 minimum / target 34. Permanent RSA-4096 release certificate, APK signature v3 and zip alignment verified. The public certificate fingerprint and debug-to-release transition are in [RELEASING.md](RELEASING.md). Private key material stays outside the repository and all release assets.
- **Repository presentation:** EN/RU/zh-CN structure, local links, light/dark responsive wordmarks, mobile hero, safe desktop and Android screenshots, source diff checks. The mascot PNG is copied unchanged into both launcher/package resources.

## Previous broader synthetic acceptance

The prior local source build passed full synthetic integration in a private network namespace without the user's bus/display/PipeWire: three encrypted software video streams reconstructed and decoded by FFmpeg, primary-only synthetic audio, 20 reconnects, orderly stop, fresh identity on the next Start and audio-off behavior. Input queue regressions also passed 25 repetitions (925 assertions). No transport or encoder behavior was changed during presentation/signing work.

The old MetaShare build and the accepted pre-release source remain available for rollback. The release process did not stop, replace or launch the user's live stream and did not install an APK.

## Artifact and publication checks

The Linux package baseline, bundled-library/source closure, runtime provenance and exact package smoke checks are documented in [APPIMAGE.md](APPIMAGE.md). SHA-256 checksums accompany the released APK, AppImage and source archives. Check the release tag and its exact-commit CI results; a workflow configuration by itself is not a passing CI run.

## Limits and remaining live tests

- No final release APK installation/upgrade on Quest, real portal screen/audio capture, actual remote-input injection, real GPU encode/decode or Noda coexistence test was performed by this release process. A non-debug APK has a different signing identity from the local debug build.
- Android View tests do not establish Horizon focus/IME behavior, physical hand/controller/mouse identification, MediaCodec lifecycle or audio behavior. Follow [MANUAL-TESTS.md](MANUAL-TESTS.md), especially disconnect/surface recreation and permission revocation.
- No measured latency, image-quality or comfort guarantee. Hardware codecs being registered does not prove that a particular GPU can initialize them.
- The AppImage is for x86_64 on modern Linux with the documented glibc baseline and working Wayland/portal/PipeWire services. It is not a universal distribution or old-LTS compatibility claim. The alternate Nix/Flatpak recipes were not rebuilt in this release pass.
- The media packet counter is bounded below `2^32`; there is no automatic rekey at that limit. Reconnect to create a fresh session. The code review found a possible callback/teardown overlap in the hardware decoder but did not reproduce a crash; see the review record.
- clang-format/clang-tidy were unavailable on the local host. Compiler warnings, security `-Werror`, Android lint, sanitizers, static checks and functional tests were used instead; this is not a full static-analysis pass.
