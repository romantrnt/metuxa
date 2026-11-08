# 0.2.0 release review

A second, separate code-review pass examined the pre-release baseline `bf540ef` and the release fixes on 2026-11-08. This is an engineering review with executable regression tests, not an external professional security audit or cryptographic certification.

## Reviewed

- TLS certificate pinning before the pairing secret is sent; ephemeral per-run server identity.
- Fresh independent directional AES-GCM keys/nonces, authenticated headers, bounded replay state committed only after authentication and UDP peer binding.
- Control parsing, resource/rate limits, input revocation, held-input release, client session generations and queue ordering.
- Android discovery and lifecycle paths, packaging provenance, actual dependency licensing and deliberate APK release signing.

No authentication bypass, nonce reuse, reflection acceptance or replay-window poisoning was found in the inspected transport. This statement describes the review result, not a guarantee that no flaw exists.

## Fixed before release

**Discovery allocation bound.** The previous host limit was checked after inserting the untrusted endpoint into a set. The displayed list stopped at 32 while retained endpoints could continue growing for the scan duration. `DiscoveryBudget` now checks its 32-host cap before insertion, accounts for every received datagram before parsing, and ends the scan at 256 responses or 32 hosts. Duplicate/malformed replies consume the response budget. Pure-Java regression tests exercise saturation, a large unique-endpoint flood, duplicates and an independent next scan.

**Public-package provenance.** The old `package-native-test.sh` path was laptop-specific, used a debug APK, and did not supply the full binary dependency notices/source closure. It remains a test-only helper. The release uses a clean Linux build baseline, explicit bundled-library/source collection, license material and a separately signed non-debuggable APK. See [BUILD.md](BUILD.md), [RELEASING.md](RELEASING.md) and [THIRD-PARTY-NOTICES.md](../THIRD-PARTY-NOTICES.md).

## Remaining reliability validation

- `VideoDecoder` recovery is handled on the codec callback thread, while its release is initiated by the session worker. A teardown/recovery overlap is possible from code inspection; no hardware crash was reproduced. Do not treat static/JVM tests as proof of MediaCodec behavior. Repeated disconnect, surface recreation, pause/resume and decoder-error tests on real Quest hardware remain valuable. A later lifecycle change should serialize cleanup on the callback thread and test delayed recovery, duplicate release, late callbacks and partial initialization before shipping.
- Although the wire field is 64-bit, each media key accepts counters only from 1 through `2^32 - 1`. At the limit media fails closed; automatic renewal is not implemented. Reconnect to obtain fresh keys. The time to exhaustion depends on actual packet rate, so there is no fixed session-duration guarantee.
- Real portal/GPU/input revocation and headset focus/IME/device churn are not established by isolated tests.

## Checks

The review reran C++ and Java security tests, Java/OpenSSL interoperability, sustained heartbeat/input-state exchange, SessionInputQueue concurrency tests, protocol/input sanitizer tests and lifecycle/mocked-discovery sanitizer tests. All passed without sanitizer diagnostics. The exact final release verification and its limits are recorded in [VERIFICATION.md](VERIFICATION.md).
