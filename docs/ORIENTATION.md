# Desktop orientation

A physical monitor is mirrored with its desktop orientation. Metuxa does not change the physical display, use Mutter DisplayConfig to apply settings, or rotate the captured image. Change a physical display in the computer's display settings; restart sharing after changing its mode during an existing session.

An owned virtual monitor can use landscape or portrait independently. The server swaps the desktop width and height through PipeWire format negotiation. Mutter changes the virtual monitor mode, so applications receive a changed desktop work area and can reflow. The existing virtual monitor, ScreenCast session and PipeWire node remain alive. Other monitor pipelines keep running.

## Sources and permission

The portal backend enables this operation only when it requested exclusively VIRTUAL (`types = 4`) and the granted stream's `source_type` is also VIRTUAL (`4`). A missing or unexpected type disables orientation. Physical monitors, windows, test patterns and unknown sources never accept it. The direct Mutter backend owns its RecordVirtual stream and supports the same operation. Portal-created virtual monitors work without compiling the optional direct Mutter backend.

This is a capture-session control, separate from remote keyboard/mouse permission. It requires the paired, authenticated monitor connection and an explicit orientation selection. A virtual monitor still belongs to the same desktop; the compositor may adjust its layout and move windows to fit the new work area. Stopping sharing removes the owned virtual monitor as before.

## Optional protocol v5 extension

The authenticated HELLO includes:

```json
"display": {"source": "virtual", "orientation": true}
```

`source` is `physical`, `window`, `virtual`, `test` or `unknown`. Missing capabilities mean unsupported. Older clients ignore this extra object and use the existing v5 flow unchanged. New clients never send orientation commands to older servers.

After READY/START, the client can send one of:

```text
DISPLAY_ORIENTATION portrait
DISPLAY_ORIENTATION landscape
```

The server responds:

```text
DISPLAY_RESULT ok 1080 1920
DISPLAY_RESULT unsupported
DISPLAY_RESULT busy
DISPLAY_RESULT failed
DISPLAY_RESULT restart_required
```

Dimensions in `ok` are the effective desktop and encoder dimensions. The server prepares the new encoder, requests an exact PipeWire size, then waits up to 1.5 seconds for a frame with that size. Success is never reported just because PipeWire accepted the request. A failed request attempts to restore the same source's previous mode, with another 1.5-second frame deadline.

During an accepted request, the affected monitor's media and input are paused and held input is released. After either `ok` or `failed`, the client reconnects that panel using its existing per-run pairing credentials. The new authenticated HELLO supplies effective dimensions, codec and fresh media keys. Other panels are not reconnected. Repeated commands on the old connection receive `busy`; requests are also limited to one every two seconds per monitor across reconnects. Commands before READY are rejected.

The per-monitor signaling thread serializes a resize and the following reconnect, so a new peer cannot receive an intermediate HELLO. An accepted operation may finish if its requesting peer disconnects; it cannot queue a later operation for the new peer. On restoration failure the server sends `restart_required` before closing the affected monitor connection. The client stops automatic retries and asks the user to stop and start sharing on the PC. It does not restart the host session itself. This result is used only after confirmed restoration failure; ordinary `failed` responses retain their reconnect behavior. No global display restoration is attempted.

## Upstream behavior

- The [public ScreenCast API](https://flatpak.github.io/xdg-desktop-portal/docs/doc-org.freedesktop.portal.ScreenCast.html) returns source type and stream metadata but has no physical-monitor orientation setter.
- The [GNOME portal](https://raw.githubusercontent.com/GNOME/xdg-desktop-portal-gnome/main/src/gnomescreencast.c) creates virtual streams without a fixed modes list, allowing the same resolution negotiation.
- Mutter's [virtual-stream implementation](https://gitlab.gnome.org/GNOME/mutter/-/blob/gnome-50/src/backends/meta-screen-cast-virtual-stream-src.c) derives the virtual mode from negotiated video dimensions. For direct RecordVirtual sessions, fixed `modes` must be omitted because they disable client-driven mode negotiation in Mutter 50. Its [resize change](https://lists.gnome.org/archives/commits-list/2022-March/msg01692.html) retains the virtual monitor while updating its mode and reloading the monitor layout.

Support depends on the active compositor and portal. The capability means this owned virtual source may be negotiated; the result still has to be checked at runtime. No physical-monitor transform or global DisplayConfig write is used as a fallback.
