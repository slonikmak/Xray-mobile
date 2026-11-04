# VPN MVP discovery

Status: requirements and delivery/testing plan approved; specification and tickets published

## Confirmed requirements

- Implement Android first and add iOS later; retain Kotlin Multiplatform.
- Use the same provider as the existing XRay-mini Windows project.
- The user supplies a link and the application obtains connection settings from it.
- Link entry is an in-app text field with paste support. Android Share integration is deferred.
- The supplied example is an HTTPS subscription endpoint, not an individual protocol link. Inspection confirmed a Base64-encoded list of VLESS profile links.
- The intended MVP includes selecting a server, connecting, checking connectivity, switching servers, and disconnecting.
- The initial testing environment is Android Emulator; the eventual iOS version is initially for personal use.
- After a connection failure, show an error and a manual Retry action; do not automatically reconnect.

## Proposed implementation direction

- Share the small UI through Compose Multiplatform.
- Prove one working connection before expanding profile management.
- Retrieve the supplied subscription before the first connection; do not require the user to extract a protocol link manually.
- Observe actual Android VPN state and report connection-check results separately.
- Verify a real browser request inside the emulator as well as connection and disconnection; running Xray alone is insufficient.

## Approved testing approach

- Test user-facing application commands and observable state at one application boundary. Substitute the subscription source and platform VPN adapter for deterministic tests, without coupling tests to internal classes or method sequences.
- Use synthetic subscription data for the observed VLESS/XHTTP/TLS and VLESS/TCP/REALITY formats. Exercise malformed responses, unsupported entries, download failures, and preservation of the previous usable list on refresh failure.
- Exercise permission denial, cancellation, connection failure, manual Retry, stop, switching profiles, and late results from a prior connection. A stale health result must not revive a stopped connection.
- Run device integration checks separately in Android Emulator with the real Android VPN service and Xray: browser traffic through the chosen endpoint, DNS behavior, IPv4/IPv6 routing policy, disconnect restoring ordinary networking, switching profiles, and returning to the UI while the service is active.
- A successful HTTPS check is limited reachability evidence; neither a running core nor one check establishes that all traffic uses the VPN. Do not infer iOS or physical-device compatibility from emulator results.
- Local provider credentials are only used for explicit manual integration checks; automated fixtures and output contain no live credentials.

## Next workflow step

The user approved the testing approach and the three sequential delivery slices on 2026-11-04, and requested specification and tickets. See [the specification](spec.md), [01: subscription import](issues/01-import-subscription.md), [02: real VPN](issues/02-connect-vpn.md), and [03: switching and manual retry](issues/03-switch-and-retry.md). All are ready-for-agent; only 01 has no blockers. The previously open product questions have been answered; do not ask them again.

## Design handoff added on 2026-11-04

The user supplied [a Pencil document](../../design/xray-mobile.pen) and requested that implementation agents use it. [The design handoff](../../docs/agents/design.md) records verified frame IDs, access methods, MVP adaptations and visual acceptance. The specification, all three tickets and the root agent instructions point to it. This adds a visual source and validation criteria without replacing the approved subscription/VPN behavior with the older proxy/JSON workflow drawn in the design.

## Environment observed on 2026-11-04

- Android Studio, its bundled JDK, and Android SDK are installed.
- An AVD named Medium_Phone_API_37.0 exists; no emulator was running at inspection time.
- The repository uses local Markdown specs and tickets; there is no application code yet.

## Sanitized provider response inspection on 2026-11-04

- HTTP 200, Content-Type text/plain; decoded from Base64 into five profile links.
- Three VLESS profiles use XHTTP with TLS.
- Two VLESS profiles use TCP with REALITY and the xtls-rprx-vision flow.
- These are response-format observations, not successful connection tests or guarantees that the subscription contents will remain unchanged.
- An initial HTTP request failed; subsequent requests succeeded. Retrieval needs a bounded timeout and a user-visible retry path.

## Handling provider examples

Do not place live subscription URLs, credentials, or downloaded provider configurations in tracked files, test fixtures, or diagnostic output. Record only sanitized format metadata here. Use synthetic fixtures for automated parser tests.
