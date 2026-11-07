# Attribution and distribution notes

## MetaShare

Source ancestry: https://github.com/makemake-kbo/metashare
Baseline: ed13beb32a705c268a9cfd5ac3c055c9e4ada433.

The original `LICENSE` is preserved unchanged: MIT License, Copyright (c) 2026 makemake. Original Git history and source attribution are retained. metuxa is an independent development; it is not represented as an upstream maintainer release.

## Runtime/build dependencies

FFmpeg, x264, OpenSSL, PipeWire, sdbus-c++, GTK/gtkmm/glibmm, json-glib, and Android/Java components retain their own licenses. Android builds use the Android platform APIs; the APK contains no FFmpeg or x264 copy in this Java implementation.

The existing FFmpeg recipe enables GPL and libx264. Linking or distributing that FFmpeg configuration can impose GPL obligations on the combined binary distribution. The MIT source notice alone is not an adequate binary-distribution license notice. A public package must include applicable licenses, exact corresponding source/build recipes and any required source offer or source access. Dynamic linking is not assumed to remove these obligations.

The initial native test folder is built for the user's existing laptop and uses its system libraries, with a copy of the previously verified sdbus-c++1.5 shared library. It is not a universal Linux AppImage. Before a public APK/AppImage release, review the actual dependency closure and license obligations, sign the APK with a deliberate release identity, and test installation/upgrade/rollback.

No public repository content, GitHub release, package upload or automated publish step is performed by this local work.
