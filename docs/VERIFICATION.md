# metuxa 0.2.0 local verification

## Passed

- Native Linux build: server with Wayland portal/PipeWire support and GTK4 controller. The build used GCC16, FFmpeg libavcodec63, OpenSSL3.6.5, PipeWire1.6.9, sdbus-c++1.5, GTK/gtkmm4, json-glib1.10.8. A local, guarded header compatibility shim handles this laptop's development GTK/GLib snapshots without changing installed headers.
- Meson tests: packet/protocol, strict input and release lifecycle, software encoder source resizing and Opus, secure transport, GTK settings/process supervision.
- ASan/UBSan: input/protocol, security transport and lifecycle/discovery tests. The latter mock source health/portal grants/discovery networking and make no real capture or input requests.
- Cross-language security: Java SSLSocket and OpenSSL interoperability, wrong fingerprint/token rejection, exact AES-GCM wire vector, encrypted RTP, invalid/replayed/wrong-source RTCP rejection, release on disconnect, live input-state updates and a sustained session beyond the heartbeat timeout.
- Full application synthetic integration in a private network namespace with no user D-Bus/display/PipeWire: three encrypted software video streams reconstructed and decoded by FFmpeg, primary-only synthetic audio, 20 reconnects, orderly stop, fresh identity on next Start, audio-off behavior.
- GTK interaction tests in a private headless Weston compositor and private D-Bus without activation services: normal and compact/dark layouts, settings, Start/Stop/failure states using a synthetic child, close/cancel, code hiding, live input controls, diagnostic filtering, no orphan child even after abrupt controller death. Screenshots show synthetic sessions, not a captured desktop.
- Android APK assembly and lint: 0 errors, 8 retained warnings. Pure-Java input/lifecycle/endpoint/paste/diagnostic tests and static architectural checks passed. APK signature v2 and zip alignment verified. Debug package identity is `dev.metuxa.quest`, separate from the old application.
- Original rollback folder checksum verification: all listed artifacts and start scripts match their existing SHA256SUMS. The old installed apps were not replaced or launched by this work.

## Not performed / remaining acceptance

- No metuxa install or launch on Quest, no real portal screen/audio capture, no real remote input, no live hardware encoder/decoder or Noda test. Android instrumentation and real device UI screenshots were not run. In particular, focus/IME behavior must be tested in Horizon OS.
- No numeric latency/quality/comfort guarantee. Encryption CPU cost and three-panel behavior need measurement on the actual headset.
- No independent security audit. Standard crypto primitives and negative tests do not establish an audited custom transport.
- Native test folder is specific to the laptop's installed system libraries. Nix/Flatpak recipes were adapted but not rebuilt; a portable AppImage and signed release APK remain separate release work after live acceptance.
- C++ clang-format/clang-tidy were not run because those tools are absent from the current environment. Native GCC warnings, security `-Werror`, Android lint, sanitizer and functional tests were used; this is not represented as a complete static-analysis pass.
- Android lint retains warnings for Quest-only ABI, the custom fingerprint-pinned trust manager and untranslated programmatic UI strings. UI text is currently English; the test-start instructions are provided in Russian.
- No GitHub repository content, release, deployment or other public artifact was published.
