# Display and appearance fixes

Verified on 2026-11-08 against the development changes following 0.3.0. These results describe local builds, not a newly published release. The source version and Android version code remain unchanged until release preparation.

## Behavior

- Physical screens mirror the PC. The client no longer applies saved image-rotation angles, and the server rejects orientation commands for physical screens, application windows, unknown sources and test patterns.
- An explicitly granted, session-owned virtual monitor can change between portrait and landscape. The compositor receives new desktop dimensions, while the captured image remains upright. Only that monitor reconnects after the encoder is ready. A failed change attempts to restore its previous mode; confirmed restoration failure stops retries and asks for a deliberate sharing restart.
- Before the first primary panel opens, Quest reads authenticated HELLO dimensions without READY or media, closes that metadata connection, and supplies stream-based window hints. Saved sizes and landscape defaults remain a fallback. Returning to an active panel does not probe or interrupt it. Horizon still controls outer panel bounds and can preserve a previous size or ignore a hint.
- Android controls use graphite settings rows, switches and compact toolbars. Advanced pointer settings remain available in an expandable section.
- System appearance follows the Settings portal, with GNOME and GTK fallbacks. Explicit Light, Dark and High contrast remain separate from the desktop preference.

See [Desktop orientation](ORIENTATION.md) for source checks, protocol messages and upstream references.

## Passed

### Native and packaged-runtime checks

- Host build with portal support and all eight Meson suites: protocol, input, encoder resize/audio, security, desktop controls, updates, appearance and appearance without GNOME schemas.
- The same eight suites on the existing offline Ubuntu 24.04 AppImage toolchain, using GCC 13.3, gtkmm 4.10 and PipeWire 1.0.5. A separate QA AppDir contains the updated control application and server. Its bundled-library GTK tests and actual control startup passed in private Weston. Existing released AppImages were not replaced.
- Both capture backends compile in the separate Mutter-enabled build.
- Full JDK 17 Java/C++ transport, input-queue and pairing suite. Additional authenticated tests cover virtual-only orientation, repeated requests, input release, disconnect during a change, reconnect dimensions, failure recovery and explicit restart-required handling.
- An authenticated metadata-only HELLO connection can close before READY without media, input or a streaming callback. The next normal connection receives fresh media keys and starts successfully.
- Native lifecycle and discovery ASan/UBSan suites, including synthetic orientation success, idempotence, rollback and fail-closed paths.

### Real compositor check

The isolated test starts installed Mutter 50.5 and PipeWire 1.6.9 with a software renderer. It masks the user's home, runtime sockets and hardware devices, and disables networking. No user desktop is captured.

Three cycles between 640×360 and 360×640 passed. The test reads Mutter's actual DisplayConfig state, checks matching captured-frame dimensions and a normal image transform, and verifies the original monitor's mode remains unchanged. The owned monitor retains its stream path and PipeWire node. Session close removes it and restores the initial monitor set.

This test exposed two old direct-backend defects: destroyed signal subscriptions prevented startup, and a fixed RecordVirtual modes list rejected later portrait dimensions. Subscriptions now live until event-loop shutdown, and dynamic virtual modes come from PipeWire negotiation.

### Desktop appearance

The actual host GNOME preference was read without changing it. It was `prefer-dark`; portal ReadOne returned a uint32 inside one variant, and legacy Read returned nested variants. Both reply forms are covered.

Private-bus and Weston tests cover live dark/light updates, switching explicit themes back to System, service restart, stale replies, destruction during pending reads, missing schemas, native GTK fallback and High contrast cleanup. The same behavior was checked with host GTK 4.25 and the stable packaged runtime. Appearance ASan/UBSan checks passed.

### Android

Offline debug and unsigned release assembly passed. Both lint variants report 0 errors and 17 warnings; the warnings are retained, not suppressed. All 21 native Android View/probe/shutdown tests passed: 15 View/state tests, three metadata-probe tests and three stream-shutdown tests. Core checks passed with 614 input/discovery assertions, 209 monitor/window/orientation checks, 237 update/security checks and 51 media-lifecycle checks.

View checks cover narrow layouts, 150% text, settings scrolling, retained form data, ignored legacy image rotation, physical-source explanations, virtual orientation choices, panel sizing, overlay opacity and persistent restart-required errors. Metadata checks cover AUTH-only bytes, valid dimensions, complete background close before launch, cancellation and timeout fallback. The first panel applies authenticated or cached portrait hints before creating its Views. Independent final code review found no remaining blocking issue.

The README screenshots are sanitized renders from these UI tests. They show real Android Views and GTK widgets, not a physical Quest capture.

## Limits and one sanitizer finding

- No physical Quest or Horizon OS was installed or exercised. Actual outer-window sizing, MediaCodec transitions, pointer behavior after a real portal resize and application-specific window reflow still require the [manual acceptance checks](MANUAL-TESTS.md).
- The real-compositor check uses the direct Mutter path in a private session. It does not exercise GNOME's real portal picker or the user's desktop. The GNOME portal uses the same dynamic virtual-stream mechanism, but its consent flow remains a separate live check.
- If the user changes the physical display's mode in GNOME during sharing, restart sharing to renegotiate it. Metuxa does not modify that display or automatically restart sharing.
- The real-compositor ASan/UBSan run completed its mode changes without an invalid-access or undefined-behavior finding, but LeakSanitizer failed with 3150 bytes in 34 allocations originating in PipeWire context/module initialization. A separate program containing only `pw_init`, context creation/destruction and `pw_deinit` reproduced exactly the same report. This is not claimed as a clean leak check. The focused metuxa lifecycle and appearance sanitizer suites passed.
- clang-format and clang-tidy are absent from this computer; `scripts/lint.sh` was not run. No software was installed to hide that gap.
- No signed update, public release or CI run for a new published commit is claimed. Release versioning, signing, complete packaging inventories and artifact checks remain part of release preparation.
