# metuxa 0.4.1 verification

Release preparation: 2026-11-08. Android and Linux version are 0.4.1; both update
codes are 6. Stream protocol remains 5. This patch separates the stable Quest
application/launcher name from runtime version text. Linux runtime behavior is
unchanged except for release/update metadata. The existing 0.4.0 release remains
available as the prior-version reference.

## Passed before source freeze

- The application label is the fixed, non-translatable `metuxa`. All three
  Activities and launcher intent filters inherit it; there is no versioned
  metadata override. The generated runtime version text remains in the launcher
  footer, Settings, initial Updates status and requested Activity window titles.
- Offline JDK 17 / Gradle 8.7 / Android SDK 34 debug and unsigned release builds
  passed. Both lint variants report 0 errors and 17 retained warnings. Core
  checks passed: 614 input/discovery assertions, 209 monitor/overlay checks,
  architecture invariants, 122 palette checks, 237 update/security checks and
  51 fake-Android media-lifecycle checks.
- All 22 Robolectric tests passed with zero failures, errors or skips: 16
  View/state tests, three metadata-probe tests and three stream-shutdown tests.
  The new test resolves actual application, Activity and launcher-intent labels;
  verifies runtime text against package metadata; and rechecks title, footer,
  Settings and launcher identity after Activity recreation. Existing stopped
  stream coverage also checks the versioned runtime title. The narrow launcher
  and 150% Settings version renders were inspected.
- The final arm64-v8a APK is non-debuggable `dev.metuxa.quest`, version 0.4.1/code 6,
  minimum API 32 and target API 34, with stable label `metuxa`. The existing
  RSA-4096 production signer, APK v3 signature and alignment were verified.
  Size: 1,258,691 bytes. SHA-256:
  `907d7ef810e7b76775d590fc81ab0e98254e4acb0d4be7d0fc9b6ddcf494a9b6`.
- A fresh application build with the pinned Ubuntu 24.04 AppImage toolchain
  passed all eight Meson suites. Previously built dependency/runtime inputs were
  reused only after their pinned source/runtime and inventory hashes matched;
  the application build and AppDir were freshly created.
- The new AppDir passed the isolated packaged GTK suite and actual control-app
  startup in private Weston. Its clean Ubuntu closure has 133 ELF objects with
  no unresolved libraries; the highest required glibc symbol is GLIBC_2.38.
  The supported host baseline remains glibc 2.39. Source/license correspondence
  covers 115 Ubuntu binary packages, 93 source packages and 135 nonempty license
  files. The mascot bytes are unchanged.
- Release versions, update codes and future-version test fixtures were reviewed.
  The stable-label source fix had an independent read-only code/APK review with
  no blocking findings. The [initial fix record](QUEST-LABEL-VERIFICATION.md)
  is historical evidence from before the release version bump.

## Pending at source freeze

Final outcomes and exact hashes belong in the
[0.4.1 release notes and assets](https://github.com/romantrnt/metuxa/releases/tag/v0.4.1),
not in a retroactively edited source archive:

- Standalone Java/C++ security, input queue, pairing and focused host sanitizer
  checks; final AppImage packaging, deterministic repeat, extracted-file
  integrity and synthetic packaged-media integration.
- Exact-commit application source archive and complete dependency-source bundle,
  including source/build recipes, patches, notices and inventory correspondence.
- Signed final update manifest and offline checks with the unchanged production
  public key, exact 0.3.0/0.4.0 updater code, private-copy atomic replacement and
  0.4.1 exact-current recognition.
- Remote commit/tag, exact-commit CI, complete uploaded assets and checksums,
  public latest manifest and actual production-updater download verification.

## Limits

No physical Quest, Horizon library cache, Android installer or live user-session
portal test was performed. No app data was cleared, no package/component identity
was changed, and no Home/headset restart was requested by the app. The stable
label cannot force an old Horizon library entry to refresh immediately. A normal
headset restart after ending a session is only a possible diagnostic step;
it is not a guaranteed cure or required update step. In-app version text or
Android package metadata remains the reliable installed-version check.

Outer-window titles are requested through Android; Horizon may ignore them.
No new claim is made about headset codec performance, live pointer/IME behavior,
GPU encoding, comfort or portability beyond the tested baseline. The earlier
[0.4.0 record](VERIFICATION-0.4.0.md) and [display checks](DISPLAY-VERIFICATION.md)
remain historical evidence, not tests automatically rerun for this patch.
clang-format and clang-tidy are unavailable; `scripts/lint.sh` was not run.
These checks are not an independent security audit.
