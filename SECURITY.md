# Security policy

metuxa streams desktop content and may forward input. Use it only on a trusted local network. Do not expose its listeners through public port forwarding. Read the [security model and limitations](docs/SECURITY.md) before use.

## Reporting a vulnerability

Do not publish working exploit details, pairing secrets, credentials, captured desktop content or personal network information in a public issue.

Check this repository's **Security → Advisories** page for a **Report a vulnerability** action. Use it if GitHub private vulnerability reporting is enabled. If it is unavailable, open a minimal public issue requesting a private reporting channel, without technical exploit details or sensitive data, and wait for the maintainer to provide one.

A useful private report includes the affected commit or release, platform versions, a minimal reproduction, expected and actual behavior, and the potential impact. Use synthetic content and disposable test credentials. Do not test on systems or headsets you do not own or have permission to assess.

Use the latest available fixes. Older releases do not have a promised maintenance window.

## Scope

Relevant areas include pairing and certificate verification, encrypted media, replay protection, discovery handling, input authorization and release, diagnostic privacy, process cleanup and dependency packaging.
