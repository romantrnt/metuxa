# Quest stable-label verification

Checked on 2026-11-08 against the post-0.4.0 source fix. This is a local source
verification, not a new release or a physical-headset acceptance result.

## Passed

- Offline JDK 17, Gradle 8.7, Android SDK/build-tools 34 build:
  `gradle --offline --no-daemon :app:assembleDebug :app:assembleRelease :app:lintDebug :app:lintRelease :app:testCore`.
  Both APK variants built; both lint reports have 0 errors and 17 existing warnings.
- Core suite: 614 input/discovery assertions, 209 monitor/overlay checks,
  architectural invariants, 122 palette checks, 237 update/security checks and
  51 fake-Android media-lifecycle checks passed.
- `client/quest/tests/ui/run.sh` offline: all 22 Robolectric tests passed with
  zero failures, errors or skips (16 View/state, three metadata-probe and three
  stream-shutdown tests). The new check resolves actual application, Activity
  and launcher-intent labels as `metuxa`; the version matches packaged metadata.
  Runtime title, footer and Settings retain the version after recreation and
  reopening Settings without changing package or launcher labels. The existing
  stopped StreamActivity test also checks its runtime title.
- Android `aapt dump badging` and resource/manifest inspection of both built
  APKs confirmed application label `metuxa`, version text `metuxa v0.4.0`,
  package `dev.metuxa.quest`, version 0.4.0/code 5, and no Activity/intent-filter
  label overrides. The unsigned release is non-debuggable. The existing signed
  0.4.0 artifact still has application label `metuxa v0.4.0`.
- Actual Android View renders of the narrow launcher and 150% Settings footer
  were inspected: the version remains visible. An independent read-only source
  and APK review found no blocking issue. `git diff --check` passed.

## Limits and delivery

`adb devices -l` returned no attached devices. No Quest app was installed,
launched, stopped or cleared, no Home restart was performed, and no user session
was inspected or interrupted. No on-device in-place migration or Horizon cache
refresh was tested. A normal headset restart after the session ends is only a
possible diagnostic step, not a guaranteed refresh or mandatory update step.

Native code was unchanged, so native build/sanitizer suites were not rerun.
Version/code, production signer, package/component identity and published 0.4.0
assets are unchanged. A later authorized release must bump version/code and use
the existing signer; these development artifacts are not replacement 0.4.0
release assets. See [identity and migration architecture](UPDATES.md#quest-library-name-and-installed-version).
