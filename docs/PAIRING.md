# Pairing with a comparison code

On the PC, start metuxa and choose **Pair a headset**. On Quest, select the PC and choose **Connect** with the manual fingerprint and token fields empty. Both screens show a code such as `04159 21357`. Compare every digit, then confirm on both screens. Reject either screen if the codes differ or you did not start the request.

The PC opens pairing for 60 seconds. One request uses that window. A failed, rejected or cancelled request needs a new window; the app does not retry pairing automatically. There can be only one pending request across all monitors. The PC admits at most ten requests per server run and requires two seconds between attempts. Stop and Start creates a new certificate and revokes the old token.

The displayed IP address helps identify a connection but does not prove which device sent it. Discovery results, hostnames and device names do not establish trust.

## What the comparison authenticates

PAIR1 uses TLS 1.3, SHA-256 commitments, independent 256-bit random nonces and an explicit comparison on each device. During this exchange, the Quest socket accepts the server certificate provisionally. It obtains the fingerprint from the certificate in that completed TLS handshake. It does not fetch a fingerprint over a separate connection or trust an application response that names one.

Before the PC sends its random challenge, Quest commits to its random nonce and the negotiated certificate. Quest reveals the nonce after receiving the challenge. The PC checks the commitment before showing an approval request. A network attacker terminating two different TLS connections has different certificate bindings. The commitment prevents the attacker from changing its nonce after learning the other side's challenge and comparison result. Nonces, request identifiers and byte ordering are all covered by the transcript hash.

The comparison contains 32 bits, displayed as ten decimal digits with leading zeros. Its values range from `00000 00000` through `42949 67295`. The grouping is exactly five digits, one space, then five digits. Comparing only a suffix or approving an unexpected request defeats this check.

An active forgery has approximately a one-in-2^32 chance per independent trial, assuming SHA-256 behaves as expected and users compare all digits. Ten decimal characters do not provide ten decimal digits of entropy. The PC's limits restrict request admission; repeated user-started attempts on either device still create additional trials. This comparison has a smaller authentication margin than checking the full fingerprint. It is not a substitute for full-fingerprint verification where that margin is required.

PAIR1 is an application-specific commitment-based comparison exchange. It does not implement Bluetooth pairing or a PAKE.

## Wire exchange

PAIR1 is a versioned extension alongside the existing stream protocol. Streaming and discovery remain version 5. Old clients can continue using manual pinned `AUTH`; old servers reject `PAIR1`, and the new client reports that pairing is unavailable. It never downgrades automatically to plaintext, an unverified stream, or a weaker pairing mode.

All lines below are ASCII followed by one LF. Hex fields decode to the exact byte counts shown. Hash input domains are ASCII followed by one NUL byte; field contents are decoded bytes, not their hex representation. Concatenation is unambiguous because all fields have fixed lengths.

- `F`: SHA-256 of the leaf certificate's DER encoding, 32 bytes.
- `C`: fresh Quest random nonce, 32 bytes.
- `S`: fresh PC random nonce, 32 bytes.
- `I`: fresh PC request identifier, 16 bytes.
- `H`: SHA-256.

```text
commit = H("metuxa-pair1-client-commit" || NUL || F || C)
comparison_hash = H("metuxa-pair1-compare" || NUL || F || C || S || I)
comparison = first four bytes of comparison_hash, unsigned big-endian
```

```text
Quest -> PC: PAIR1 <commit:64 hex>
PC -> Quest: PAIR_CHALLENGE1 <I:32 hex> <S:64 hex>
Quest -> PC: PAIR_REVEAL1 <C:64 hex>
PC -> Quest: PAIR_WAIT1
PC -> Quest, after local approval only: PAIRED1 <token:32 hex>
PC closes this connection.
```

Each side derives its own comparison code. Neither receives the code from the network. The PC rejects a reveal whose commitment differs, an unexpected line, extra application data, a disconnected peer, or an expired request. No `HELLO`, media keys or input callbacks run on a pairing connection.

The server-issued token is accepted by the Quest connection coordinator only after both a `PAIRED1` response and the user's local **Codes match** confirmation. A malicious server can claim to have received approval, so its response alone cannot advance the UI or start a stream. Cancelling the Quest attempt suppresses a queued result even if the response already arrived. A new stream connection verifies the approved full fingerprint inside the TLS trust manager before sending `AUTH`.

The TLS handshake and first request each have a five-second deadline. The reveal has at most five seconds, bounded by the original pairing window. The whole PC request expires when that original 60-second window ends. Client reads and its local confirmation wait share a fixed 60-second deadline. Requests never extend their deadline by sending traffic. Both implementations retain the existing 4096-byte line bound.

## Private desktop approval

The control application passes a private read-only pipe as `--control-fd N`, separate from the private status pipe. The server checks that it is an owner-only FIFO, owned by the same user, and distinct from the status descriptor. Pairing stays disabled without that pipe. Terminal users can use manual pairing.

```text
PAIR_OPEN
PAIR_CLOSE
PAIR_ACCEPT <I:32 hex>
PAIR_REJECT <I:32 hex>
```

Only an exact, live, displayed request identifier can be approved. A decision cannot be replayed or changed after it is consumed. Closing the pipe or sending `PAIR_CLOSE` removes a pending request. The final approval check, token write and removal of that request serialize with cancellation and expiry under the same lock. A close or expiry that wins before issuance prevents the token write; cancellation cannot retract a write that has already started under valid approval.

The private status pipe reports `pairing_window`, `pairing_request`, `pairing_closed` and `pairing_unavailable` events. These carry the pending identifier, comparison code, numeric peer address and remaining deadline. They are not ordinary diagnostic logs. The control application must bind its confirmation buttons to the exact request currently displayed and disable them when that request ends.

## Manual fallback and limits

Manual pairing still compares the entire SHA-256 fingerprint and enters the per-run 128-bit token. Existing pinned streaming uses TLS 1.2 or newer and the same authenticated media transport. Successful comparison pairing keeps credentials in memory; it creates no persistent unattended trust grant. Explicitly saved manual profiles follow the existing encrypted-token storage policy.

This does not defend against a compromised PC, headset, OS or another process with the same user's local access. Attackers can still cause denial of service, consume an open pairing window, interfere with traffic or block a connection. Use a trusted local network and do not expose the listeners through public port forwarding.

## Tests

Run `sh tests/security/run-pairing.sh` with JDK 17 available through `JAVA` and `JAVAC`. The suite is also included in `tests/security/run.sh`. It checks matching C++/Java hash vectors, differing certificate bindings, commitment mismatch, unsolicited requests, extra commands, missing reveals, stale identifiers, cancellation, explicit rejection, both confirmations, early remote success, one-shot token issuance, the attempt cap and real 60-second expiry. Java then reconnects to the native TLS server using the approved pin and token. These tests use synthetic TLS peers without capture or input devices.
