# metuxa

A Linux desktop control app and a Quest launcher for protected, low-latency desktop streaming in ordinary Horizon Home windows.

metuxa develops [MetaShare](https://github.com/makemake-kbo/metashare), starting at commit `ed13beb32a705c268a9cfd5ac3c055c9e4ada433`. The original MIT copyright and license remain in [LICENSE](LICENSE), and the Git history is preserved. This local development build is not a public release.

## What it does

- GTK4 control app with saved settings, quality profiles, actual server status, separate pairing details, bounded activity, and safe subprocess cleanup.
- Quest launcher with explicit server selection, discovery and manual address, saved preferences, connection controls, and coordinated monitor windows.
- Up to three panels: a physical desktop and optional virtual monitors. Single-window capture is available as a separate, view-only portal mode, subject to desktop support.
- H.265/H.264, hardware encoding where available and software fallback, paced RTP, loss feedback and retransmission.
- System audio or no audio. The primary panel owns audio so opening extra panels does not duplicate playback. Microphone capture is not exposed as a supported feature.
- Shared, saved input controls across Quest panels: movement/hover, clicks/drag, scroll and keyboard independently. Full remote, Keyboard only, View only and Custom presets; physical-pointer allow/deny lists. Home hand tracking and the laptop's own physical mouse/keyboard are not changed.
- Protected control and media: pinned TLS, a random per-run pairing secret, AES-256-GCM RTP/RTCP, replay checks, no plaintext fallback.

## First test

1. Stop the old MetaShare stream yourself when ready to test. Keep the original APK and server installed for rollback.
2. Start `metuxa-control` from the supplied test folder. Choose one desktop, 72 fps, HEVC, and system audio. Remote control is off by default; enable it before Start if wanted.
3. Press Start and answer the desktop's capture/control permission dialog. Capture granted without input devices must show view-only capability. Cancelling or rejecting the request stops that attempt rather than opening another permission picker.
4. Open the separate metuxa APK on Quest. Choose the PC or enter its IPv4 address. Enter the exact SHA-256 fingerprint and pairing code shown on the PC, then Connect. Never approve a fingerprint that does not match the PC.
5. Try the shared pointer setting. **Block pointer** prevents all pointer forwarding from video panels, including hands, controller rays and a mouse connected to Quest. Keyboard forwarding has its own toggle. With movement disabled, allowed clicks act at the existing laptop cursor; no new coordinates are sent.
6. Disconnect and stop the server after use. Restarting the server invalidates the previous pairing details.

Both devices still need a reachable local network. The default discovery port is UDP 7877; TLS signaling is TCP 7878, 7879 and 7880 for panels 1–3; encrypted media uses negotiated UDP ports. The legacy application uses different ports and identity. Do not expose this test build to the public internet.

## Hand input: the honest limit

This is a normal Android 2D app, not an OpenXR application. Horizon OS can translate tracked hands and controller rays into indistinguishable Android pointer events. Blocking all forwarded pointer events works without changing hand tracking in Home. Selected-device filtering can retain a separately identifiable physical mouse. A universal reliable “hands only off, controller rays on” switch cannot be promised without event evidence on the headset/OS in use.

The opt-in input diagnostic records bounded pointer-device metadata only. It does not record typed text, key values, pointer coordinates, desktop pixels or audio. Use it to compare hands, controller rays and a physical mouse before selecting a device filter. See [manual tests](docs/MANUAL-TESTS.md).

## Build and review

- [Build instructions](docs/BUILD.md)
- [Security design and limitations](docs/SECURITY.md)
- [Manual acceptance checklist](docs/MANUAL-TESTS.md)
- [Third-party notices and distribution](THIRD-PARTY-NOTICES.md)

Native build dependencies: C++20, Meson/Ninja, OpenSSL, FFmpeg codec/util/swscale/swresample, PipeWire, sdbus-c++ **1.5**, GTK4/gtkmm4 and json-glib. Android uses JDK17, Gradle8.7 and the installed SDK selected by `ANDROID_HOME`.

No unattended daemon, cloud relay, hidden microphone capture, OS security changes, automatic application installation, or automatic release publication is included. Existing MetaShare clients are intentionally not compatible with metuxa protocol 5.

## Release status

The local test artifacts must pass live Quest/Wayland/Noda testing before distribution. Automated tests cannot establish motion-to-photon latency, comfort, hand/controller differentiation, hardware encoder reliability, or real audio playback. AppImage packaging and a signed public APK are separate release steps, not claims about this test bundle.
