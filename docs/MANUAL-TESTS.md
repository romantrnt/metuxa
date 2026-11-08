# Live acceptance checklist

Use a test desktop and headset you are ready to interrupt. Keep the previous release available for rollback. Record PASS, FAIL or NOT TESTED with the OS/headset versions. [Automated results](VERIFICATION.md) cover isolated sessions and synthetic media; they do not replace these checks.

## Launch and pair

- Opening either app must not start sharing. Discovery suggests PCs without connecting. Invalid address, port, fingerprint or token must show a useful error.
- Cancel the portal picker, deny control, close the dialog, and retry. Status must match the result; repeated Start must not create duplicate servers. Stop must close a pending capture request.
- Open **Pair a headset** on the PC. Compare all ten digits and confirm on both screens. Repeat with mismatched digits, unexpected requests, Cancel on either side, expiry and server restart. Cancelled or unconfirmed attempts must not stream or forward input.
- Cancel an attempt and immediately begin another. An old dialog must not approve or cancel the new request. Closing the Quest launcher cancels its pending attempt.
- Test manual pairing with the exact fingerprint and token. Wrong credentials, legacy MetaShare and unrelated LAN servers must not receive desktop video or control. Stop/Start changes the server identity; saved credentials must fail until deliberately replaced.
- Enable auto-connect for one saved PC and restart Quest. Only that endpoint may be selected. Forget it and confirm automatic connection stops.

## Desktop and media

- Share one 1920×1080 display at 72 fps. Compare H.265 hardware and H.264 software encoding; check the reported encoder and readable small text.
- Try audio Off/System with one, two and three panels. Audio must play once, through the primary panel only. No microphone capture.
- Secondary panels must use the selected PC, never another discovery offer. Close/reopen them twenty times. Closing the primary disconnects the others.
- Try view-only single-window capture. The portal must offer an actual application window, not silently share a whole monitor; availability depends on the portal.
- Run for 30 to 60 minutes, including alongside Noda if used. Record stability and comfort. Measure latency separately rather than inferring it from frame rate.

## Input

- With host remote control off, neither keyboard nor pointer input may reach Linux. Enable it before Start, grant the portal permission, then test click, drag, scroll, Ctrl/Alt/Shift, navigation, Unicode/Russian text, Backspace and Enter.
- During a drag or held modifier, block pointer input, disable remote input on PC, disconnect Wi-Fi, pause Quest, lose focus, and stop the app. No remote key or button may remain held.
- Open all panels and set **Block all pointer devices** once. Hands, controller rays and a Quest-connected mouse must not forward pointer actions in any panel, including newly opened ones. Home hand tracking, the PC mouse and native app controls must remain usable.
- Test Move/hover, Click, Scroll and Keyboard independently. Movement off with clicks on must click at the existing PC cursor without moving it. Clicks off must never synthesize a drag. Scroll-only must not move or click.
- Disable scroll with a partially accumulated wheel step; it must not leak later. Disable keyboard during a paste or held modifier; pending text must stop and held keys release.
- Compare **Full remote**, **Keyboard only**, **View only** and **Custom** with the actual switches. Reopen the launcher and panels to check persistence.
- Test both physical-device filters. Unknown and virtual sources must stay blocked. Replugging a selected mouse may change its identity; stale selections must be removable.
- Drag with an allowed mouse while a blocked hand/controller hovers, clicks or scrolls. The blocked source must not move or release the mouse drag. Unplugging the owner must release its buttons.
- Re-enable input while a mouse button is still held, then press another button. The old button must not return without a release and fresh press.
- Hold remote Ctrl, focus a native toolbar button, then release Ctrl. Linux must not retain the modifier. Click the viewport and confirm remote keyboard input resumes.
- Test IME composition and a 300-character paste followed by Enter. Text must arrive once and in order. Losing focus or disconnecting cancels unsent text visibly. An oversized paste must be rejected rather than truncated.
- The Quest virtual keyboard produces keyboard input even when selected by hand. Blocking pointer devices must leave it available; the Keyboard switch must block it.

## Panels and appearance

- Rotate each monitor through 0°, 90°, 180° and 270°. Check decoded image orientation, all four corners, drag, hover, scroll and keyboard focus. Resize portrait and landscape windows. Reconnect and verify per-PC/per-monitor rotations persist independently.
- Test the top and bottom bars independently in Always, Hover and Hide after a delay modes. Use mouse hover, controller rays and hands. **Controls** and **More** must keep every action reachable. Touching a local control must not click the remote desktop.
- With two panels resumed, change bar mode, opacity and keep-awake from either panel and the launcher. Both panels must update.
- Change theme/text size, resize, minimize, switch focus, return Home, close and reopen. Repeat disconnect/reconnect during recreation. Check for frozen video, duplicate audio, overlapping decoder ownership or unreleased input.

## Diagnostics and recovery

- Turn metadata collection on explicitly. Compare hands, each controller and a physical mouse using source/tool/device metadata. If hands and controller rays are indistinguishable, do not assume hand-only filtering is possible.
- Check the 80-sample buffer and rate limit. Reset, disable and restart; samples must clear. No typed text, coordinates, pairing secrets, frames or audio may appear.
- Run twenty connect/disconnect cycles, sleep/wake, surface recreation, Wi-Fi loss and server restart. Returning to settings or receiving another launcher intent must not abandon the active session or revive a stale one.
- Close the Linux controller while running and terminate during pairing. No orphan stream may survive. Diagnostic export must exclude pairing details, typed text, coordinates, frames and audio.

## Installation and updates

- Install signed 0.3.0 over 0.2.0 without uninstalling. Saved preferences must survive and Android app info must show `metuxa v0.3.0`. Record separately what Horizon's Unknown Sources library displays.
- Updates start disabled. Enable checks and optional automatic downloads. A downloaded release must never install by itself, and an installer prompt must not interrupt active streaming.
- Exercise the first future signed update: check, download cancellation/retry, permission denial, Android confirmation cancellation, installation, insufficient storage and process death. Settings must survive. Changed signers, modified assets and older releases must be rejected.
- Keep the previous AppImage and test from a private user-writable directory. Check automatic download, explicit installation, restart and manual rollback. Read-only files, symlinks and hard links must receive a useful error. See [Updates](UPDATES.md).
