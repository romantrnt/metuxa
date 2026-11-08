# Attribution and distribution licenses

## Project source and ancestry

metuxa is an independent development of [MetaShare](https://github.com/makemake-kbo/metashare), based on commit `ed13beb32a705c268a9cfd5ac3c055c9e4ada433`.

The original `LICENSE` remains unchanged: MIT License, Copyright (c) 2026 makemake. Original source attribution and Git history are retained. metuxa is not an upstream maintainer release. The MIT notice governs the project's own source; it does not replace dependency licenses or the obligations of a combined executable.

## Linux AppImage: GPL combined server

The release server links FFmpeg 7.1.5 configured with `--enable-gpl --enable-version3 --enable-libx264`. This combined server executable is distributed under **GNU GPL version 3 or later**. Dynamic linking is not treated as an exemption. `COPYING.GPLv3` is included at the AppImage root. The MIT permission and attribution on the original project source remain available alongside this combined-binary license.

The GTK control application, AppImage launcher/runtime and independent libraries retain their applicable licenses. Packaging them together does not relicense every independent component under GPL. OpenSSL 3 is Apache-2.0 licensed and the FFmpeg configuration enables GPLv3 compatibility. No `--enable-nonfree` components or proprietary GPU driver binaries are bundled.

The release contains both source archives below, beside the AppImage, without a source-request fee:

- `metuxa-0.4.2-source.tar.gz`: the exact published metuxa source revision, including the build and packaging scripts.
- `metuxa-0.4.2-dependency-sources.tar.gz`: the actual bundled dependency source archives, exact Ubuntu source-package versions and Debian/Ubuntu patches, the pinned Alpine package recipes/patches for static runtime libraries, and build records. Keep both archives available with any binary redistribution.

This is direct corresponding-source distribution, not a substitute promise to supply source later. A repository link alone is not the dependency source bundle. A release must not publish the AppImage without both source assets and their checksums. GPL terms permit modification and redistribution; applicable LGPL terms also permit modification/relinking and reverse engineering for debugging such modifications.

## Actual Linux dependency inventory

The AppImage contains:

- FFmpeg at `3a0867c2bfda4a4d4309ca1a8cbdc6175e67f587` (7.1.5), GPL-3.0-or-later for this configuration. Only the x264/Opus encoders and NVENC/VAAPI encoder interfaces needed by metuxa are enabled.
- x264 at `b35605ace3ddf7c1a5d67a2eb553f034aef41d55`, GPL-2.0-or-later; distributed as part of the GPLv3 combined server.
- sdbus-c++ 1.5.0 at `30d9f1d46258c2be4ce29b7f7f168ecdf6d4f328`, LGPL-2.1-or-later with its included header exception.
- NVIDIA codec interface headers at `157becbf51c8b813425572b75c06c370bd43d8fd`, MIT. These are build inputs, not an NVIDIA driver distribution.
- GTK/gtkmm, GLib/glibmm, PipeWire, Opus, OpenSSL, libcurl, font/rendering and other supporting libraries from the Ubuntu 24.04 package closure. Individual copyrights and licenses are in `usr/share/licenses/ubuntu/`; referenced common license texts are in `usr/share/common-licenses/`.
- Fontconfig 2.15.0 from exact Ubuntu source `2.15.0-1.1ubuntu2`, rebuilt with the documented `fontconfig-template-dir.patch`. The patch makes passive template discovery relocatable, avoiding incompatible newer-host configuration. It changes no system files.
- An AppImage type-2 runtime rebuilt locally from official commit `fedfad51acc2860b2916a1eef32ba4a500ff31ef`. It is not the upstream signed binary. Runtime code is MIT; statically linked libfuse 3.15.0 is LGPL-2.1, squashfuse 0.5.2 is BSD-2-Clause, musl 1.2.5 is MIT, mimalloc 2.1.7 is MIT, zstd 1.5.6 is distributed under BSD-3-Clause, and zlib 1.4 uses the zlib license. Exact Alpine package revisions, patches, original sources and rebuild scripts are supplied. FUSE's helper/service/device installation is disabled during the container build.

Machine-readable inventories inside `usr/share/metuxa/` identify exact binary/source packages, file hashes, pinned source commits, build toolchain versions and the dependency source archive index. They describe the actual package rather than the much broader host FFmpeg installation. The source bundle includes the full code and build scripts needed to rebuild the LGPL-linked runtime, not only its top-level source.

The target operating system supplies glibc, the dynamic loader, kernel, session services, fonts and GPU vendor drivers. These are not bundled. See [AppImage build and compatibility notes](docs/APPIMAGE.md) for the host requirements.

## Android

The Java Quest APK uses Android platform APIs. It contains no FFmpeg, x264, native OpenSSL or Linux AppImage runtime copy. Android/Java tooling and platform APIs retain their own licenses. The Android source remains covered by the project's MIT notice, subject to any separately identified third-party material.
