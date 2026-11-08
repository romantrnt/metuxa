# Linux AppImage build and compatibility

## Package boundary

`metuxa-0.4.1-x86_64.AppImage` contains the GTK control application, server, unchanged transparent Tux PNG, runtime libraries, GTK icon/schema data, SVG loader, PipeWire client modules/SPA plugins and full license inventories. It does not install a service, change system packages or share the desktop until explicitly started.

Baseline: **x86_64 Linux with glibc 2.39 or newer**, built against Ubuntu 24.04. The 0.3.0 ELF closure required at most GLIBC_2.38; the final 0.4.1 artifact check belongs in the [release results](https://github.com/romantrnt/metuxa/releases/tag/v0.4.1). The supported baseline stays at the tested 2.39 environment. Ubuntu 22.04 and older are not supported by this build. Other recent distributions are candidates, not a certified compatibility matrix.

The host still supplies the kernel, glibc/loader, desktop session, fonts, a working Wayland desktop portal, PipeWire service, and GPU vendor drivers. Software encoding is bundled. NVENC/VAAPI registration is checked, but real hardware encoding is not certified by an isolated test. Do not infer GPU, portal or headset compatibility from `--validate`.

The GUI defaults to GTK's Cairo renderer to avoid depending on a particular host GPU stack. This does not disable hardware video encoding. Set `GSK_RENDERER` explicitly to opt into another GTK renderer. Matching Fontconfig rules are bundled, plus a documented environment-path patch for its otherwise hardcoded passive template scan. Host fonts remain available. No host configuration is rewritten.

## Usage

```sh
chmod +x metuxa-0.4.1-x86_64.AppImage
./metuxa-0.4.1-x86_64.AppImage                   # Control panel
./metuxa-0.4.1-x86_64.AppImage --help            # CLI options; no capture
./metuxa-0.4.1-x86_64.AppImage --validate        # No capture/network/input
./metuxa-0.4.1-x86_64.AppImage --server --help   # Explicit server dispatch
```

If FUSE is unavailable, use `--appimage-extract-and-run` before the application options. No FUSE helper or device is installed by metuxa. Manual extraction is also supported by the standard `--appimage-extract` runtime option. An AppImage is a packaging format, not a security sandbox.

## Source and license assets

Always distribute both `metuxa-0.4.1-source.tar.gz` and `metuxa-0.4.1-dependency-sources.tar.gz` with the binary. The first is a Git archive of the exact published source revision. The second holds original dependency archives, distribution patches/build recipes and checksums. The GPL-enabled server's combined distribution is GPL-3.0-or-later; original project source retains its MIT notice. See [THIRD-PARTY-NOTICES.md](../THIRD-PARTY-NOTICES.md).

Inside the AppImage, `usr/share/metuxa/` contains:

- `ubuntu-packages.json`: actual Ubuntu binary/source package correspondence.
- `bundled-files.json` and `FILES.sha256`: dependency origins and final file hashes.
- `sources.lock.json`: pinned upstream commits and the actual rebuilt runtime hash.
- `ubuntu-build-packages.lock`, `alpine-build-packages.lock`: exact build-environment packages.
- `application-source.sha256`: application source inputs for comparison with the Git source archive.
- `SOURCE-INDEX.json`: hashes and sizes of the dependency source files.

Full license texts are included, including referenced Debian common licenses and the separately linked static runtime libraries. NVIDIA headers are MIT; proprietary GPU drivers are not bundled. The runtime is locally rebuilt from official AppImage source, not represented as an upstream-signed executable.

## Repeatable build recipe

Requirements on the build host: existing Podman, Git, Python 3, curl, gzip, tar and sufficient disk space. Use containers only; do not install build dependencies on the desktop. Approximately 3–4 GiB of local build workspace plus container storage is sufficient for this configuration. The supported build host is Linux x86_64.

The recipes pin upstream source commits, archive checksums, container images and exact distribution package versions. Repeating the SquashFS packaging step over the same verified AppDir is checked separately. A fresh independent toolchain rebuild has **not** been proven bit-for-bit reproducible. Stable APT/APK repositories can retire old versions; the recipe fails rather than silently choosing newer packages. The distributed source archives and package patches remain available for rebuilding even if that happens.

From the repository root:

```sh
export WORK="$PWD/.release-work"
mkdir -p "$WORK"
python3 scripts/appimage/fetch-sources.py
python3 scripts/appimage/fetch-runtime-sources.py
podman build -t localhost/metuxa-appimage-toolchain:ubuntu24.04 scripts/appimage

# The source tree is read-only; only the release workspace is writable.
podman run --rm -v "$PWD:/source:ro" -v "$WORK:/work:rw" \
  localhost/metuxa-appimage-toolchain:ubuntu24.04 bash -c '
    bash /source/scripts/appimage/build-dependencies.sh
    bash /source/scripts/appimage/build-fontconfig.sh
    bash /source/scripts/appimage/build-application.sh
    python3 /source/scripts/appimage/collect-appdir.py
    python3 /source/scripts/appimage/collect-sources.py
    dpkg-query -W > /work/ubuntu-packages.tsv
  '

podman run --rm -v "$PWD:/source:ro" -v "$WORK:/work:rw" \
  docker.io/library/alpine@sha256:2d8a11041a92082ba21e364dec73559ce44f1de69b60fa68315f6d3774e59117 \
  sh -c 'apk add --no-cache bash=5.2.37-r0 && bash /source/scripts/appimage/build-runtime.sh'

python3 scripts/appimage/finalize-appdir.py
podman run --rm --network=none -v "$PWD:/source:ro" -v "$WORK:/work:rw" \
  localhost/metuxa-appimage-toolchain:ubuntu24.04 \
  bash /source/scripts/appimage/pack.sh /work/metuxa-0.4.1-x86_64.AppImage
scripts/appimage/package-dependency-sources.sh "$PWD/dist/metuxa-0.4.1-dependency-sources.tar.gz"
```

For a clean collection retry, remove only the generated `.release-work/AppDir`, rerun `meson install --no-rebuild` with `DESTDIR=/work/AppDir` inside the builder, then rerun the collector. Do not collect over a stale AppDir. Packaging refuses to overwrite existing output files. If a rebuilt runtime differs from the recorded hash, inspect the changed inputs and audit it before updating the lock; do not disable verification.

`build-runtime.sh` preserves the upstream runtime implementation and FUSE patch. Its build-only change disables installing setuid helpers, service scripts or device nodes. The runtime's static source closure includes libfuse, squashfuse, musl, mimalloc, zstd and zlib; Alpine package patches are retained in the exact `aports` snapshot. Fontconfig's small relocation patch and its exact Ubuntu source are included as well.

## Verification and limits

The release workflow checks:

- Ubuntu-baseline compile and all eight Meson test suites, including appearance with and without GNOME schemas.
- CLI help/validation and codec registration without capture or network.
- No unresolved linked libraries in the collected ELF closure, with glibc/loader supplied by the target OS.
- Private Weston UI tests using the packaged libraries, synthetic mock-server events and no host display, session bus, PipeWire socket, GPU/input devices or network. These cover presets/source constraints, pairing visibility, live-input gates, close cancellation and shutdown. The actual packaged control executable also starts in that isolated session.
- Unchanged mascot PNG bytes and desktop-entry validity.
- Deterministic AppImage assembly from identical AppDir/runtime inputs and source/license completeness gates.

The [0.4.1 source-freeze verification](VERIFICATION-0.4.1.md) separates completed checks from packaging and publication still pending at that point. Final artifact outcomes and hashes belong in the [release notes and checksums](https://github.com/romantrnt/metuxa/releases/tag/v0.4.1). The [0.3.0 results](VERIFICATION.md) remain historical evidence; a successful development AppDir check does not establish the final AppImage result. Synthetic integration uses only loopback in a private network namespace and synthetic video/audio; it does not validate real capture, hardware drivers or Quest installation. The app can check for signed GitHub updates while open. Installation verifies the complete file, saves a backup and replaces the AppImage atomically. No update daemon or system service is installed. See [Updates](UPDATES.md) for permissions, recovery and the manual first transition from 0.2.0.
