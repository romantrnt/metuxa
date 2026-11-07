# Live acceptance checklist

Run these only when ready to stop the old stream. Keep the original desktop folder and installed MetaShare unchanged. Automated tests use synthetic media/isolated sessions and cannot replace this checklist.

## Start and pair

- Start from the test bundle. Confirm settings persist but a session does not start merely by opening the UI.
- Cancel the portal picker, deny control, close the dialog, and retry. Status must be honest, the button must not create duplicate servers, and Stop must close a pending capture request.
- Pair with the exact PC fingerprint and code. Wrong code, wrong fingerprint, legacy MetaShare and an unrelated LAN server must not receive desktop video or control.
- Stop/Start. Old pairing details must fail, and the new pair must work.

## Desktop and media

- One 1920×1080 display at 72fps; compare H.265 hardware and H.264 software. Confirm displayed encoder and readable small text.
- Off/System audio; one, two and three panels. Audio must play once from the primary panel. No microphone capture.
- Confirm second/third panels connect to the selected PC, never a different discovery offer. Close/reopen them twenty times.
- Try the view-only single-window portal mode. Check that the OS offers an actual window and does not silently show a whole monitor; availability depends on portal implementation.
- Run 30–60 minutes beside Noda. Measure subjective stability/comfort and, if needed, film an external latency comparison rather than infer latency from fps.

## Input

- Remote control off; keyboard/pointer must not reach Linux.
- Enable control before Start, accept portal permission, test a mouse click/drag/scroll, Ctrl/Alt, Unicode/Russian text, Backspace and Enter.
- During a drag or held modifier: block pointer, disable remote input on PC, disconnect Wi-Fi, pause Quest, lose window focus, and stop the app. No remote key/button should remain held.
- Open all panels, set Block pointer once, and confirm hands/controller rays/Quest mouse do not forward pointer actions in any panel. Home hand tracking and a physical laptop mouse remain usable.
- Test each independent action switch: movement/hover, clicks/drag, scroll, hardware/IME keyboard. With movement off and clicks on, the PC cursor must not move and clicks must target its existing location. With clicks off, moving over a panel must not synthesize a drag.
- Disable scroll with a partially accumulated wheel step; it must not leak into later input. Disable keyboard during a paste/held modifier; pending text must stop and held keys release.
- Compare Full remote, Keyboard only, View only and Custom preset labels with the actual switches; reopen the launcher and new panels to verify persistence.
- In pointer-device filters, allow only selected physical descriptors or all identifiable physical descriptors except selected. Unknown and virtual devices must fail closed. Full unfiltered mode is separate.
- Drag with an allowed mouse while a blocked hand/controller hovers, clicks or scrolls: the blocked source must not move, release or re-press the mouse drag. Unplug the mouse during a drag and confirm its buttons release.
- Reset/re-enable input while a mouse button remains held, then press a different button. The old held button must not be resurrected without a release and a fresh press.
- Press remote Ctrl, focus a native toolbar button, then release Ctrl. The laptop must not retain the modifier, and native controls must still receive their own input.
- Keyboard-only behavior is explicit. Text entered using a hand on the Quest virtual keyboard is still text, not a distinguishable hand-pointer event; disabling keyboard blocks that channel too.

## Device diagnostic

Enable the metadata diagnostic deliberately. Exercise hand hover/pinch/poke, each controller ray, and the physical mouse separately. Compare source, tool type, device name/descriptor and buttons; do not infer origin from timing or coordinates. If hands and controller rays have identical metadata, do not label a filter “hands only”. Clear the diagnostic afterward.

## Recovery and shutdown

- Twenty connect/disconnect cycles, Quest sleep/wake, surface recreation, Wi-Fi loss and server restart.
- Closing the controller while running, repeated Start/Stop, and termination during pairing must not leave an orphan stream or frozen input.
- Diagnostic export contains no pairing code, keypress/text, coordinates, frames or audio.

Record PASS/FAIL/NOT TESTED and relevant OS/headset versions. Do not call the build release-ready merely because it compiles.
