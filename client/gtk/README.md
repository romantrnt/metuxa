# metuxa control

A native GTK4/gtkmm application that supervises `metuxa-server`. It does not
capture, encode, or connect to the headset by itself. Opening the app never
starts sharing. The new application ID is `dev.metuxa.Control`; installation
must not replace `dev.metashare.StreamerUI` or the original MetaShare binaries.

## Workflow

1. Choose a capture source and quality. The initial defaults are one screen,
   72 fps, HEVC, an 8 Mbps per-screen ceiling, and system output audio only.
2. Keep **Allow remote control** off for view-only, or enable it explicitly for
   this session before Start. This consent is never restored on the next launch.
3. Start the server and approve the system capture dialog when applicable.
   Starting, requesting permission, listening, connected, and error are distinct
   states reported by the server, not inferred from process existence.
4. In **Pair headset**, choose **Pair a headset**. Connect from Quest, compare
   all ten digits and confirm on both screens. Manual fingerprint/token pairing
   remains available. Each server run has new credentials. See the
   [pairing protocol](../../docs/PAIRING.md); do not expose the ports publicly.
5. During an input-enabled session, use the live remote-control switch to block
   or re-enable input without restarting video. This never grants new portal
   permissions. Stop, or choose **Stop and close** when closing an active session.

Screen source uses the portal-selected physical display followed by virtual
screens where the compositor supports them. Window source is one selected
application window and is always view-only. GNOME mode uses the Mutter virtual
backend for extra screens. Test pattern never requests capture, sound, or input.
Real headset and compositor behavior still requires device testing.

## Configuration and privacy

- Capture preferences are atomically saved in `$XDG_CONFIG_HOME/metuxa/control.ini`
  (normally `~/.config/metuxa/control.ini`) with private creation permissions.
- No microphone setting exists. Neither credentials nor input consent are saved.
- Pairing credentials arrive through dedicated FD 3, never command arguments or
  mixed stdout/stderr. The code is concealed until Reveal or an explicit Copy.
- Activity keeps at most 300 bounded, UTF-8-sanitized lines. Sensitive fields,
  keyboard-event details, and long hexadecimal credentials are omitted.
- Diagnostic export contains settings and up to 200 structured lifecycle/stream
  records. It deliberately excludes raw logs, addresses, fingerprints, pairing
  codes, and captured content. The user explicitly chooses the destination.
- The controller uses a private child process group, nonblocking bounded pipes,
  and a single child watch. Stop sends SIGTERM then a cancellable 3-second SIGKILL
  deadline. No restart is possible before the previous child is reaped.
- Parent death uses Linux `PR_SET_PDEATHSIG` with SIGKILL, including a fork/exec
  race check, so a GUI crash cannot leave its capture process alive. Normal close
  first asks whether to stop sharing and waits for exit.

Appearance and update preferences are available in **Settings**. Update checks
and downloads are opt-in; installation is explicit and requires sharing to stop.
See [Updates](../../docs/UPDATES.md) for signatures and AppImage replacement.

## Server status contract

`metuxa-server --status-fd 3` writes one JSON object per newline. Lines over
8 KiB or invalid JSON are dropped. Recognized events:

```json
{"state":"starting"}
{"state":"requesting_access"}
{"state":"listening"}
{"state":"connected"}
{"state":"error","message":"Useful non-secret failure detail"}
{"state":"stopped"}
{"type":"security","fingerprint":"64 hexadecimal characters","pairing_code":"32 hexadecimal characters"}
{"type":"stream","monitor":0,"codec":"hevc","encoder":"hevc_vaapi","width":1920,"height":1080,"fps":72}
{"type":"capability","monitor":0,"input_available":true,"input_active":true,"audio_active":true}
```

Monitor indices are zero-based, 0–2. Only explicit capability events enable the
live input switch. SIGUSR1 blocks input, SIGUSR2 re-enables only initially granted
input. Input-event payloads must never be included in the status channel.

## Build and tests

Dependencies: gtkmm >=4.10, GLib >=2.68, json-glib, and the root server dependencies.
The root Meson build produces `client/gtk/metuxa-control` and installs the new
desktop entry/icon. It resolves a sibling `metuxa-server`, the paired Meson build
server, or the `metuxa-server` on PATH; never the old MetaShare server.

`meson test -C build metuxa-control --print-errorlogs` runs pure configuration,
privacy, event, and synthetic process-lifecycle tests. It opens no windows and
uses no real server, network, capture, input, or user configuration. It verifies
bounded pipes, malformed settings, failure exit, repeated Start/Stop, forced
shutdown, stale timer cancellation, destructor cleanup, and abrupt-parent death.

For headless UI integration, build the `metuxa-ui-tests` target, then run:

```sh
client/gtk/tests/run-isolated-ui.sh \
  "$PWD/build/client/gtk/metuxa-ui-tests" "$PWD/build/ui-screenshots"
```

This requires an already installed Weston and dbus-run-session. It creates a
private headless Wayland compositor, an activation-free D-Bus session, and
new temporary XDG directories. It never uses the user's session and only launches
its own synthetic mock child. It checks source/preset changes, capability-driven
UI, private pairing, live input gates, cancel-close, and stop-close. Optional PNGs
contain documentation-only addresses and synthetic, hidden credentials.

`gtkmm_compat.hpp` isolates three wrapper class-alias conflicts in the host's
GTK 4.25 / GLib 2.90 development snapshot with older gtkmm/glibmm bindings. It
changes no installed headers, object layouts, or instance/function types. Stable
SDK builds take the normal include path. Release packaging should use a matched
stable runtime and SDK.
