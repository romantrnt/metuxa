# Signed GitHub updates

Versions 0.3.0 and 0.4.0 can check for and download the signed 0.4.1 update; installation remains explicit. Version 0.2.0 has no updater, so install 0.4.1 manually from that version, using the official APK with the same signing identity or replacing the AppImage. Do not uninstall the Android app: a normal signed update keeps settings and pairing data. Debug-signed installations cannot accept production updates.

Both clients retrieve `https://github.com/romantrnt/metuxa/releases/latest/download/update-manifest.txt`. GitHub supplies hosting and TLS; the existing metuxa release key authenticates the manifest independently. The public key is in `data/update-public-key.pem`. Private signing files stay outside the checkout. New signing identities or key rotation require a reviewed app release and are not accepted through a downloaded manifest.

The envelope starts with one standard Base64 RSA/SHA-256 PKCS#1 v1.5 signature line, followed by the exact signed ASCII payload:

```text
metuxa-update-v1
version=0.4.1
version_code=6
protocol_min=5
protocol_max=5
android_size=123
android_sha256=<64 lowercase hexadecimal characters>
linux_size=456
linux_sha256=<64 lowercase hexadecimal characters>
```

Every line, including the final line, ends in LF. Fields have a fixed order with no duplicates or extra fields. Version components are canonical decimal integers from 0 through 999. Other integers are positive canonical decimal values at most 2147483647. The manifest is capped at 16 KiB, APK at 256 MiB and AppImage at 512 MiB. Clients verify the signature before parsing payload fields, require a greater semantic version and release code, and require their protocol version to fall in the signed range. These releases use stream protocol 5. The Android client also remembers its highest authenticated compatible release and refuses older signed announcements, including after a failed download. Downgrades are rejected by the updater; keeping a previous Linux file is for deliberate manual recovery.

Asset locations are derived from the verified version and fixed repository, never supplied as arbitrary manifest URLs:

- `releases/download/vVERSION/metuxa-VERSION-arm64-v8a.apk`
- `releases/download/vVERSION/metuxa-VERSION-x86_64.AppImage`

Downloads allow bounded HTTPS redirects only to GitHub and its named release asset hosts, verify TLS normally, enforce size and time limits, and verify the signed SHA-256 digest. Clients do not execute downloaded scripts. A valid signature proves release-key authorization, not compatibility with every headset, GPU or distribution. There is no online revocation service or transparency log. A compromised signing key requires a manual trust migration; a network attacker can withhold an update but cannot authenticate a replacement release.

## Android installation

The APK package, version and signer must match the signed release and the pinned production certificate. The installed app must have the production signer too. The updater writes a private cache file, verifies it, and submits it to Android PackageInstaller with user action required. Android may require the user to allow installation from metuxa in system settings and confirm each update. A normal unprivileged app cannot promise silent installation on Quest. Receiver status persists across activity recreation. Failed/cancelled downloads remove temporary files, failed uncommitted sessions are abandoned, and committed sessions belong to Android. On the next install attempt, the updater also recovers its own uncommitted sessions left by process death. No uninstall or data-clear operation is used.

Close/cancel stops a download and prevents callbacks into a destroyed UI. Android continues an already committed installation independently. Complete or cancel an outstanding installer prompt before starting another install. Device testing must cover permission denial, confirmation cancellation, process death, insufficient storage and successful upgrade over the previous signed APK.

## Quest library name and installed version

Published 0.3.0 and 0.4.0 APKs used `metuxa vVERSION` as the Android application
label. A successful 0.3.0 → 0.4.0 headset upgrade was reported with `metuxa v0.3.0`
still in Unknown Sources while the running window showed 0.4.0. This is consistent
with a stale Horizon library entry; it is not evidence that the APK failed to
update. That particular headset cache has not been inspected directly.

Version 0.4.1 separates persistent identity from runtime version text:

- `@string/app_name` is the fixed, non-translatable `metuxa` application label.
  Activities and launcher intent filters inherit it without a version override.
- Gradle generates `@string/app_version_label` from `versionName` for the launcher
  footer, Settings, initial Updates status and the running Activities' requested
  window title.
  Check the in-app version or Android's installed-package version, not an old
  Unknown Sources label. Horizon may ignore the requested window title.
- The package ID, launcher component, signing identity and stored preferences
  remain unchanged. Version 0.4.1 uses code 6; stream protocol remains 5. No alias
  rotation, component disabling, uninstall, data clear or Home restart is used to force a refresh.

Android documents [Activity-label inheritance](https://developer.android.com/guide/topics/manifest/activity-element#label)
and [intent-filter-label inheritance](https://developer.android.com/guide/topics/manifest/intent-filter-element#label).
These describe package metadata, not when Horizon refreshes Unknown Sources.
No documented app-level guarantee of an immediate Horizon refresh was found.
The stable label prevents future release numbers from becoming stale once the
library has reread it; it cannot evict an older cached label by itself.

The published 0.4.0 APK is not changed retroactively. Install the signed 0.4.1
update in place with the same signer.
After that in-place update, first reopen the library. If its old label persists,
a normal headset restart when no session is in use is a non-destructive check,
not a guaranteed cure or a requirement for the app update. Do not force-stop
Home during an active session, clear Home or metuxa data, uninstall metuxa, or
factory-reset the headset to fix a cosmetic label. Preferences and pairing data
must survive the normal signed upgrade.

## Linux installation

Automatic replacement supports the running x86_64 AppImage. A native package, missing `APPIMAGE`, read-only file, symlink, hard link or directory writable by other users requires a manual update. Move the AppImage to a private writable folder you own if needed.

Opt-in downloads create a private staged file. Installation verifies the signed manifest and exact file bytes again, preserves an independent fsynced backup, and atomically replaces the current AppImage. Stop sharing and explicitly choose installation first, then restart metuxa to run the new version. The updater does not execute the download or restart sharing. A signed receipt beside the AppImage prevents replay below a prepared release, including after a crash; retrying that same authenticated candidate is allowed. A crash before the swap leaves the original file usable and independently backed up. Linux records this floor during installation preparation, not when merely checking an announcement; previously seen but unprepared releases do not establish a persistent floor.

For each new release, bump `current_code` in `client/gtk/update_manager.cpp` together with the Android version code and Meson version. Tests cover rejected redirects, invalid signatures/fields, replay, file identity, atomic replacement, retained backup and retry after a pre-swap crash. These tests use synthetic inert AppImage headers and never execute downloaded binaries.

## Producing a release manifest

Build and sign the release APK, build the clean-baseline AppImage, and complete the release checks first. Then run with the existing JDK and Android SDK:

```sh
JAVA_HOME=/path/to/jdk17 ANDROID_HOME=/path/to/android-sdk \
  bash scripts/sign-update-manifest.sh 0.4.1 6 5 5 \
  dist/metuxa-0.4.1-arm64-v8a.apk \
  dist/metuxa-0.4.1-x86_64.AppImage \
  dist/update-manifest.txt
```

The helper verifies the APK signer, package, version and non-debuggable status before signing. It uses the existing `metuxa-release` identity and refuses to overwrite its output. It creates no credentials. Publish the manifest and both binary assets under tag `v0.4.1`, alongside the required exact source and dependency-source archives. Upload everything before promoting the release to latest. Keep source/code/protocol numbers consistent across clients and manifest. Never sign substitute bytes merely to test a public download path.

Run `bash client/quest/tests/run-update-tests.sh` for standalone verifier tests. They use ephemeral in-memory test keys and cover tampering, wrong identity, rollback, incompatible protocol, invalid field layout, oversized payloads/assets and repeated verification. They do not install an APK or prove on-device installer behavior.

Verify final manifest and artifact bytes offline before uploading:

```sh
JAVA_HOME=/path/to/jdk17 bash scripts/verify-update-manifest.sh \
  dist/update-manifest.txt dist/metuxa-0.4.1-arm64-v8a.apk \
  dist/metuxa-0.4.1-x86_64.AppImage 0.4.0 5
```

The command above checks the upgrade from 0.4.0/code 5 using the application's production public key. Without the optional final version/code arguments, the verifier defaults to 0.2.0/code 3. Neither path changes the trust pin. It also checks the authenticated exact-current announcement path. It never opens private signing files or runs the artifacts.
