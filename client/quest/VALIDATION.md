# Quest validation, metuxa 0.2.0

## Automated checks

- JDK17/Gradle8.7 offline `:app:assembleDebug`: built with the existing Android
  platform34/build-tools34. No new binaries or dependencies downloaded.
- `:app:testCore`: 289 executable assertions, plus architectural static checks.
  Tests cover endpoint/pin/token parsing; global policy decisions; virtual,
  unknown and unselected device rejection; pointer release with stale button
  flags; focus/disconnect cleanup; keyboard modifier cleanup; bounded scroll;
  Unicode paste ordering/pacing/bounds; lifecycle callback order; diagnostic
  opt-in, reset, rate and capacity limits. All 16 action combinations are tested,
  including click-only without coordinate leakage, move-only without buttons,
  scroll accumulator reset, keyboard disable/modifier release, safe allow/deny
  behavior, truthful presets and loading persisted policy for new windows. Integrated
  ownership tests cover mixed-source drag isolation, owner unplug/identity changes,
  stale mixed-button rearming, native-control modifier release, and independent
  movement/scroll while clicks are disabled or an auxiliary mouse button is held.
- The transport's separate `tests/security/run-input-queue.sh` suite verifies
  generation invalidation, dequeue/claim races, release ordering and non-blocking
  policy changes under a blocked network writer (37 assertions).
- `:app:lintDebug`: no errors. Remaining warnings are retained, not suppressed.
  They concern the intentionally Quest-only ABI, the custom fingerprint-pinned
  certificate trust manager, and untranslated programmatic UI strings. The
  device's actual certificate pin behavior is separately tested by the secure
  transport suite in the repository.
- APK signature and alignment are checked with the existing Android SDK tools.
  See the top-level artifact/build report for the final file checksum.

## What these checks do not prove

No APK installation, Quest launch, native UI screenshot, Android instrumentation,
hardware decoder initialization, input injection, Home hand/controller behavior,
IME behavior, or live audio playback was performed. JVM tests and static checks
are not a substitute for those on-device checks.

## Focused headset test checklist

1. Fresh launch stays on the launcher. Discovery alone never connects. Invalid
   address/port/pin/token shows a useful error. Wrong pin and wrong token fail.
2. Connect to a selected PC, disconnect, reconnect, return to settings, reopen
   screens, and repeat quickly. A second launcher/stream intent must not kill
   the original active panel. No abandoned decoder or stale callback should win.
3. Open/close/reopen up to three monitors. All use the same selected PC; only
   the first plays audio. Closing the primary disconnects every secondary.
4. Resize, minimize, switch focus, return Home, close and reopen panels. Held
   mouse buttons/modifiers must release; queued late input must remain blocked.
5. Test all independent Move/Click/Scroll/Keyboard switches, presets, and both
   device filters. With movement off, clicks must use the existing PC cursor
   without movement, and scroll-only must not click. Block all pointers while dragging. Test hand, controller and mouse
   events in all panels, including a newly opened panel. Remote keyboard and
   native toolbar controls must still work. Toggle back only after release.
6. Select a physical mouse allowlist, then unplug/replug. Unknown/virtual
   devices remain blocked. Record actual metadata rather than assuming which
   device is a hand or controller.
7. Test hardware Ctrl/Alt/Shift shortcuts and navigation keys, IME composition,
   backspace, Unicode, a 300-character paste followed by Enter, cancellation
   during a paste and an oversized paste. Confirm no duplicated or lost text.
8. Enable metadata collection, compare sources, inspect the 80-sample cap,
   reset it, disable it and restart the app. Verify granular switches and both
   device lists persist, and newly opened windows inherit them. No text/coordinates/secrets appear.
9. Restart the server to rotate its identity. Saved credentials should fail
   clearly until deliberately replaced. Never silently trust a changed pin.
10. Enable auto-connect for one saved host; restart the APK and verify only that
    exact host is selected. Forget it and confirm automatic connection stops.
