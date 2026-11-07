# Security model

## Session setup

An explicit server Start creates an ephemeral P-256 certificate and a 128-bit random pairing secret in memory. All monitor listeners share this run's identity. The private status pipe passes the certificate SHA-256 fingerprint and pairing secret to the local control app, separate from ordinary server logs. No persistent server private key or unattended trust grant is created.

The Quest must verify the exact expected fingerprint before sending the pairing secret through TLS. TLS 1.2 or newer is required. The server does not send stream parameters or accept input until authentication succeeds. The client never retries with plaintext or silently trusts a changed server.

The code is a per-run secret, not a six-digit PIN and not a one-connection-only credential: it can authenticate the additional monitor panels and reconnects during the same server run. Stop/Start revokes it. Anyone who learns it and the server fingerprint during that run may connect; keep the pairing display private.

## Media

Each authenticated monitor connection gets fresh, independent server-to-client and client-to-server AES-256-GCM keys and nonce prefixes over TLS. The authenticated UDP envelope covers RTP and RTCP. It includes a direction and monotonically increasing packet counter, with a bounded replay window and a per-key counter limit. Invalid authentication, wrong direction, old/replayed packets and malformed envelopes are discarded before packet parsing. No raw-RTP fallback is available.

This is an application-specific envelope built from standard TLS/AES-GCM primitives, **not an independently audited protocol or an SRTP/DTLS certification**. It needs further review and real-device testing before a public release. Traffic timing, packet sizes and local discovery metadata remain visible. Discovery offers are only hints; authentication does not trust them.

## Input and permissions

The server starts view-only unless remote control is explicitly selected. Only the desktop portal can grant capture/control. The live input gate can revoke forwarding but cannot grant a permission not obtained when capture started. Held remote keys and buttons are released on disconnect, disable and orderly shutdown. Physical host input is not intercepted.

No global hand-tracking permission is changed. A block policy applies only to pointer forwarding from metuxa's video surfaces; its own controls and Home remain usable. IME text cannot reliably reveal whether a hand or a controller selected a virtual key, so allowing keyboard input does not mean “no hand-originated text”.

## Operational limits

- Use a trusted local network. Do not configure port forwarding or a public listener.
- A network peer can still attempt denial of service; TLS does not prevent connection flooding or radio interference.
- Status/private pairing output is sensitive. Do not redirect it into a shared logfile. The GUI diagnostic export excludes pairing details and input contents.
- This does not protect against a compromised laptop, headset, OS, compositor, process debugger or malicious local user with equivalent account access.
- Ephemeral identity changes every server start. This favors explicit local testing and revocation over unattended convenience.
- No claims are made about DRM capture, privileged desktops, accessibility tools, or protection against physical access.

## Wire profile and resource bounds

- Discovery is `METUXAD1`, version 5, UDP 7877 by default. TLS starts on TCP 7878 (each additional monitor uses the next port). Discovery never carries a trust grant or pairing secret.
- The first TLS application line is `AUTH ` followed by exactly 32 hex characters. Only after its constant-time token comparison succeeds does the server send `HELLO`. The SHA-256 pin is checked inside the Android trust manager during the TLS handshake, before any application secret is sent.
- GCM datagrams are `MXG1`, a one-byte direction (1 server, 2 client), three zero reserved bytes, an unsigned 64-bit big-endian counter, ciphertext, and a 16-byte tag. The complete 16-byte header is AAD. Nonces are the per-key random 4-byte prefix followed by the counter. Counters begin at 1 and stop before 2^32; a connection must be renewed at that limit. Every retransmission gets a new counter.
- The receiver commits its 2048-packet replay window only after successful authentication. Media plaintext is limited to 1500 bytes; ordinary video RTP packets are at most 1400 bytes, plus 32 bytes of authenticated-envelope overhead.
- The client uses the authenticated TLS peer's address and HELLO's authenticated UDP port. The server accepts feedback only from the TLS peer's address and READY's exact UDP port. Both validate AEAD before RTP/RTCP parsing.
- The server echoes authenticated `PING` heartbeats. It sends `INPUT_STATE true|false` after START and each heartbeat; a live server input gate takes effect immediately, while headset controls reflect it within about three seconds. A gate cannot create missing portal permissions.
- Control lines are capped at 4096 bytes, handshake/authentication have 5-second deadlines, idle/partial server reads have 10-second deadlines, and writes have a 3-second server deadline. Control message rate and RTCP processing are capped. Input queue overflow triggers explicit release-all instead of silently losing key/button releases.

## Automated verification

`sh tests/security/run.sh` builds isolated C++ and pure-Java tests, creates only ephemeral test credentials, and opens loopback sockets without capture, audio devices or desktop input. Set `JAVA`/`JAVAC` to a JDK 17 installation if necessary. Set `SECURITY_TEST_FLAGS='-fsanitize=address,undefined -fno-omit-frame-pointer -g'` for the sanitizer variant. Meson also runs the C++ portion as the `security` test.

The suite covers valid authenticated control, wrong token, wrong certificate fingerprint, plaintext rejection, byte-fragmented/coalesced TLS messages, abrupt partial-message disconnect, line bounds, prompt shutdown, real encrypted RTP, forged/wrong-source/replayed RTCP, explicit input release and disconnect release, primary-only audio advertisement, cross-language AES-GCM wire equivalence, replay-window advancement/reordering, successful Java SSLSocket to OpenSSL interoperability, and a session longer than 12 seconds against the actual RTP server with live input disable/enable updates. Passing these tests does not establish real-headset performance or independent cryptographic audit.
