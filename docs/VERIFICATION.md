# metuxa 0.3.0 verification

Release preparation: 2026-11-08. The previous 0.2.0 artifacts and source remain available for rollback. Earlier verification records remain in Git history.

## Passed

- Native Linux build and all six Meson suites on the host and the isolated Ubuntu 24.04 release builder: protocol, input state, encoder resize/audio, transport security, desktop controls/process supervision, and signed updates.
- Linux updater: 251 checks on the host and Ubuntu baseline, also under ASan/UBSan. Tests cover signature/schema/version/protocol policy, size/digest/architecture, file permissions, symlinks/hard links, replacement, backup and retry. An independent abrupt-process-exit test verified recovery after update-record publication.
- Full C++/Java security suite: TLS authentication, wrong pins/tokens, encrypted media/replay handling, 37 input-queue assertions and the new pairing tests. Pairing covers real one-minute expiry, attempt bounds, mismatched certificate commitments, stale approvals, cancellation, fabricated early approval, two-sided confirmation and pinned reconnect. The expanded pairing suite passed ASan/UBSan.
- Actual server/private-pipe pairing integration against both native and packaged servers. Tested open, matching comparison, wrong/stale IDs, exact accept, reconnect, reject, close, pipe EOF and invalid non-pipe descriptors. Synthetic source only, private network namespace, no capture or host input.
- Desktop UI: private Weston session with synthetic server events, normal and compact layouts, appearance preferences, pairing comparison controls, stale-event rejection, live input gates, close cancellation and shutdown. The actual packaged control binary also starts with its bundled libraries in the isolated session.
- Final Android release assembly, release lint and core tests passed. Core coverage: 614 input/discovery assertions, 176 geometry/panel/surface-ownership checks, 237 update checks and 51 codec/session/socket lifecycle checks, plus architecture and palette checks. Dark/light palette checks passed, with minimum checked enabled-text contrast of 5.43:1 and 5.50:1.
- Twelve Android View/session tests passed using Robolectric/native graphics. Coverage includes dark/light/system themes, resizing and 150% font, form retention across recreation, four rotations at two sizes, inverse touch/hover mapping, overlay timing, Controls/More access, native-focus release, return to remote keyboard input and multiwindow preference updates, and the actual StreamSession.stop path with blocked TLS close and exact-socket ownership. These are Android View renders, not Horizon OS screenshots.
- Release lint retains 0 errors and 17 warnings: target API, Quest-only ABI, two certificate trust managers, a programmatic View constructor and programmatic UI strings. No warnings were hidden to claim a clean result.
- Final APK: nondebuggable `dev.metuxa.quest`, version 0.3.0/code 4, minimum API 32 and target API 34. Existing RSA-4096 release signer, APK v3 signature and alignment verified. The manifest label is generated from the version.
- Read-only Android 35 emulator: signed 0.2.0/code 3 installed, Keyboard only preference selected, then signed 0.3.0/code 4 installed in place without uninstall/data clearing. The preference survived. Android's actual app-info screen displayed **metuxa v0.3.0**.
- Corrected signed APK on Android 35: PAIR1 confirmation and encrypted H.264 playback passed; decoded pixels rotated at 0°, 90°, 180° and 270°, retaining aspect ratio. Live Light-to-Dark recreation resumed frames without a process crash or main-thread network exception. This used a synthetic source with loopback-only sockets, no desktop capture, no audio and no host input.
- The final update manifest was signed with the existing release identity and verified offline using the pinned production public key. Both APK and AppImage names, sizes and SHA-256 digests matched; protocol and upgrade/current-version policies passed.
- AppImage assembly with the same AppDir/runtime inputs produced identical bytes. The package's own server passed synthetic integration: three encrypted software H.264 streams reconstructed and decoded, primary-only test audio, 20 reconnects, orderly shutdown, fresh run identity and audio-off. No real desktop/audio capture or host input was used.
- Native lifecycle and discovery tests passed ASan/UBSan, including pipeline shutdown, source cleanup, exact discovery bounds and reply-rate limits.
- Independent security and media-lifecycle reviews found and drove fixes. The review findings are summarized below. This is not a professional external audit.

## Review findings

Pairing initially allowed a fake server to fabricate approval without an explicit comparison on Quest. Both screens now require separate confirmation; callbacks are bound to the original attempt, and final token issuance is serialized with cancellation and expiry. The short comparison authenticates 32 bits per active trial, not the full manual fingerprint’s entropy. Skipping comparison defeats its MITM protection. See [PAIRING.md](PAIRING.md).

The Linux updater now preserves an independent fsynced backup instead of a hard link, which could make a crash retry fail its safety checks. An independent abrupt-exit test verified that the old executable remained usable and the same candidate could be installed on retry. Android hashes the exact bytes copied into PackageInstaller, persists installation state before commit, and serializes rollback-floor and installer operations.

Codec work and teardown share an owner thread. A per-monitor gate keeps replacement sessions from overlapping unfinished cleanup. A real Android 35 signed-app test then exposed two issues that View-only tests missed: SurfaceView rotated the bounds but not decoded pixels, and TLS close during a theme change could perform network I/O on the UI thread. The renderer now uses TextureView with a retained SurfaceTexture lease, and socket close runs asynchronously on the exact captured socket. The session worker waits for both decoder and socket cleanup before releasing the monitor gate. The corrected signed APK passed the same runtime retest: actual decoded pixels rotated correctly at all four angles, with correct aspect ratio, and a live Light-to-Dark recreation resumed moving video in the same app process without the main-thread TLS crash. Repeated live cycles and live System-theme switching were not completed and are not claimed.

Independent final lifecycle checks also exercised 200 concurrent surface retain/retire races with 1001 assertions. No further concrete defect was found in that review. A stalled hardware codec or socket close may still delay replacement; UI stop does not wait for that cleanup.

## Package boundary

The Linux package is x86_64, built on Ubuntu 24.04 with supported host glibc 2.39 or newer. Its final collection contains 133 ELF objects from source builds and 115 Ubuntu binary packages, with 93 corresponding Ubuntu source packages. Full license texts and source inventories accompany it. The original mascot bytes are unchanged. See [APPIMAGE.md](APPIMAGE.md).

The source archive identifies the exact release commit. The separate dependency-source archive is required for the GPL-enabled FFmpeg/x264 distribution. Published assets include the signed update manifest and SHA-256 checksums. CI must be checked for the exact published commit; the workflow file alone is not evidence of a passing run.

## Not established by these tests

- No physical Quest installation, Horizon library display, controller/hand/IME behavior, Quest MediaCodec performance or comfort measurement. Android app-info label verification does not force Horizon's Unknown Sources library to use that label.
- No actual future-version self-update through the Quest system installer. Permission denial, installer confirmation/cancellation, process death, storage failure and Horizon-specific restrictions still need device acceptance. The emulator upgrade used Android's package manager, not the new in-app installation flow.
- No complete live HTTPS fault-injection matrix, online signing-key revocation service, or physical power-loss filesystem test. A compromised release key requires manual trust migration.
- No real desktop portal capture, hardware encoder acceptance, microphone capture or Noda coexistence testing in this release pass. Synthetic tests are explicitly isolated.
- A genuinely wedged native hardware codec can delay replacement while cleanup is pending. UI stop stays nonblocking; the app does not overlap a replacement with uncompleted teardown.
- Nix/Flatpak builds are not supported by this release. Unused recipes and obsolete setup helpers were removed; the supported package paths are the APK and AppImage documented in BUILD.md.
- clang-format and clang-tidy are not installed on the host. Compiler warnings, Android lint, focused warnings-as-errors builds, sanitizers and functional tests were used instead.

The first transition from 0.2.0 must be manual because that version has no updater. Follow [UPDATES.md](UPDATES.md) and the [physical-device acceptance checklist](MANUAL-TESTS.md).
