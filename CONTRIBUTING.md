# Contributing to metuxa

Small, focused changes are easier to review. Explain the problem, the intended behavior and how you checked it. For a substantial design change, open an issue before investing in an implementation.

## Before you start

- Read the [build guide](docs/BUILD.md), [security model](docs/SECURITY.md) and relevant [Quest documentation](client/quest/README.md).
- Preserve upstream history, copyright notices and dependency attribution. Do not relabel a GPL-dependent binary distribution as MIT-only.
- Keep pairing explicit, remote control opt-in on the server and errors visible. Never introduce a plaintext fallback or a silent capture/input request.
- Do not run live screen capture, microphone access, desktop input injection or headset installation as part of an unattended test.

## Development checks

For the native build:

```sh
meson setup build -Dportal=enabled -Dmutter=disabled -Dbuildtype=debugoptimized
meson compile -C build
meson test -C build --print-errorlogs
```

For security and input regressions, use JDK 17 and the native development dependencies:

```sh
sh tests/security/run.sh
bash tests/security/run-input-queue.sh
bash tests/run-review-lifecycle.sh
```

For the Android client:

```sh
cd client/quest
gradle --no-daemon :app:assembleDebug :app:lintDebug :app:testCore
```

Run relevant sanitizer checks from the [build guide](docs/BUILD.md) for native memory/lifecycle changes. If your environment supplies the required clang tools and backend dependencies, `scripts/lint.sh` checks formatting and static analysis; disclose when it was not run rather than treating other checks as equivalent.

Synthetic integration must remain isolated from the user's display, session bus, PipeWire devices and network. UI tests use a private headless session. Live device testing is a separate, deliberate activity using the [acceptance checklist](docs/MANUAL-TESTS.md).

## Pull requests

Include:

1. What changed and why.
2. Relevant regression coverage and exact commands run.
3. OS/toolchain versions for compatibility-sensitive changes.
4. Passed, failed and untested checks, with the reason for any gap.
5. Before/after screenshots for UI work, using a stopped or synthetic session with no pairing details, network addresses, personal content or names.

Keep English, Russian and Simplified Chinese README facts consistent when changing user-visible behavior. Leave interface labels in their actual language so users can find them. New protocol and input behavior needs tests, not only documentation.

Never commit signing keys, credentials, pairing codes, captured desktop content, local configuration or build caches. Review diagnostic logs before attaching them. See [SECURITY.md](SECURITY.md) for vulnerability reporting.
