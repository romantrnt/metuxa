# Media ownership regression tests

Run `JAVA_HOME=/path/to/jdk17 bash tests/run-media-lifecycle-tests.sh` from the
Quest directory. The script compiles the actual production video/audio decoder,
codec lifecycle and monitor ownership gate against small, local Android fakes.
Outputs are isolated under a temporary directory and removed on exit.

The fake codec and AudioTrack enforce a single owning thread and reject work
after release. Latches inject blocked configuration, recovery and PCM writes.
Tests cover duplicate/interrupted release, pending recovery, late callbacks,
partial initialization, release on the callback thread, audio input ownership,
and waiting/cancelled monitor replacements. This detects ordering errors; it
does not establish hardware MediaCodec, SurfaceFlinger or Quest behavior.

Teardown deliberately waits on the background session worker until serialized
codec cleanup completes. Activity stop never joins that worker. A truly wedged
native codec can therefore keep the replacement waiting, rather than being
unsafely reused after a timeout. Real Quest repeated recreation, multi-monitor
decode, disconnect and injected decoder-error tests remain required.

Socket shutdown regressions also verify that TLS close runs off the requesting
thread, repeated stop closes once, background cleanup waits for completion, and
interruption/IOException do not lose the completion barrier. The UI suite calls
the actual StreamSession.stop() path with a blocked close to prevent regressions
of Android's NetworkOnMainThreadException during Activity recreation.
