# Releasing metuxa

## Release gates

1. Keep the tested rollback build and use a separate working branch.
2. Run native tests, Java core/theme regressions, Android lint, cross-language security tests and sanitizer tests. Inspect the actual UI. Record device testing separately from isolated tests in [0.4.0 verification](VERIFICATION-0.4.0.md). Preserve the [0.3.0 record](VERIFICATION.md) as historical evidence.
3. Build the AppImage with the pinned toolchains and source/license collection in [APPIMAGE.md](APPIMAGE.md).
4. Build `:app:assembleRelease :app:lintRelease :app:testCore` with JDK 17 and Gradle 8.7. Sign only the non-debuggable release APK.
5. Freeze the source commit. Publish corresponding source, dependency source/build materials, notices and SHA-256 checksums beside the binaries. A source tag alone does not supply bundled dependency sources.
6. Sign `update-manifest.txt` with the existing release identity after both final binary assets are verified. Follow [UPDATES.md](UPDATES.md); upload all assets before promoting the draft release to latest.
7. Verify the pushed commit, exact-commit CI, uploaded asset sizes/checksums and release links. Do not mark unfinished or untested stages as passed.

## Android identity

The release package is `dev.metuxa.quest`. Its permanent signing identity is separate from local debug builds. A release APK cannot update an installed debug-signed APK with the same package ID. Users should decide whether to remove that test app first, knowing this removes its local settings and pairing data. Never uninstall automatically or recommend bypassing signature checks. The original MetaShare package is separate and can remain installed.

Release certificate SHA-256:

```text
5f5504991278b256e2f890c9b81f6fe5979c978225f24827000bd79c4e6cfdc7
```

With an existing private identity, sign a built APK:

```sh
ANDROID_HOME=/path/to/android-sdk \
  scripts/sign-android-release.sh \
  client/quest/app/build/outputs/apk/release/app-arm64-v8a-release-unsigned.apk \
  dist/metuxa-0.4.0-arm64-v8a.apk
```

The helper expects `metuxa-release.p12` and `keystore.password` under `METUXA_SIGNING_DIR` (default: `$HOME/.local/share/metuxa/signing`). It uses alias `metuxa-release`; it does not create a key, print its password, install an APK or publish a release. Keep the private directory at mode 0700 and its files at 0600. Back up both key and password together in encrypted offline storage. Losing the identity prevents ordinary updates to released APKs. Do not copy it into Git, CI artifacts, release sources, screenshots or support logs.

The AndroidKeyStore key used inside the app for saved pairing credentials is unrelated to this APK signing key.

## Public release notes

State the tested baseline, supported architecture, required portal/codec support, APK signature transition, source/license assets and remaining device checks. A successful build or second code review is not a formal cryptographic audit, proof of Linux portability, or a latency guarantee.
