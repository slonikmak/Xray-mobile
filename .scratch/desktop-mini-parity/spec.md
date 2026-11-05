# Desktop parity with X-Ray Mini

Status: ready-for-agent

Current implementation status and the next step are recorded in [STATUS.md](STATUS.md). The original Windows-oriented requirements below are retained as historical context and are superseded for current work by the macOS direction in the following section.

## Current platform direction (2026-11-05)

The user changed the immediate desktop target to macOS on the current Apple Silicon Mac. Windows implementation and checks are deferred. This direction supersedes Windows-first, DPAPI and Windows Job Object requirements below for current work; preserve the local SOCKS5/HTTP proxy contract and use macOS-native protected storage and process handling. Validate the running app on macOS and record any platform-specific limits without claiming Windows support.

## Problem Statement

The project has a working Android VPN client and a desktop JVM target for shared code, but no installable desktop app. The user wants a desktop build from this project that behaves like X-Ray Mini and uses its desktop design.

## Solution

Build a Windows 11 x64 Compose Desktop application in this repository. Its screens follow the copied X-Ray Mini Compact Pencil frames. Its behavior follows X-Ray Mini at `c22f052`: multiple saved profiles, explicit JSON or subscription/link import, loopback SOCKS5/HTTP proxies, a separately owned Xray process, connection health, diagnostics, tray behavior, and a complete distributable. Android remains a system VPN client. The desktop UI must show real state rather than mock data.

## User Stories

1. As a Windows user, I can install and open the desktop app without administrator rights and see a truthful disconnected or restored state.
2. As a user, I can import a supported Xray JSON profile, review discarded sections, and save it under a name I choose.
3. As a user, I can load an HTTPS subscription or VLESS link, preview candidates, choose one, and save it without connecting automatically.
4. As a user, I can cancel a subscription load; an expired, canceled, failed or replaced preview cannot be imported later.
5. As a user, I can view, select, rename and delete multiple saved profiles; an active profile cannot be silently deleted.
6. As a user, I can restart the app and recover saved profiles without exposing their secrets in the UI, logs or other Windows accounts.
7. As a user, I can connect a selected profile and use SOCKS5 at `127.0.0.1:10808` and HTTP at `127.0.0.1:10809` from applications I configure explicitly.
8. As a user, I can distinguish a running Xray process from a successful request through the proxy, and see degraded health without a false success claim.
9. As a user, I can cancel startup, disconnect, retry a failed stop and reconnect only after confirmed cleanup.
10. As a user, I can close the window to the tray, reopen it to current state, see connection status in the tray, and exit with owned resources stopped.
11. As a user, I can inspect safe recent activity, runtime version and proxy endpoints, and copy the endpoint addresses.
12. As a user, I can use the 360 × 600 Compact layout and a larger window with keyboard navigation, readable scaling and no clipped actions.
13. As a distributor, I can build a complete Windows package with a pinned Xray binary, Java runtime and required notices; package checks fail on missing or modified components.

## Implementation Decisions

- Windows 11 x64 is the first desktop target, matching X-Ray Mini. The desktop product is a local proxy connection, not a system VPN; Android keeps its existing VPN semantics (ADR 0002).
- Add a desktop application module using the existing Kotlin Multiplatform/Compose setup. Keep desktop-specific profile storage, Xray process ownership and UI separate from Android's VPN service. Reuse shared parsing or UI utilities only when their contracts match; do not change Android behavior to fit desktop.
- The behavioral baseline is X-Ray Mini commit `c22f052` and its existing acceptance scope. The visual baseline is the copied Compact Pencil document, SHA-256 `25c04815edbd1f7b5dc2fa26fd5b3c0900ffdf00f1e0b5e307edd19ab7d52837`.
- Preserve X-Ray Mini's explicit import rules: strict single-VLESS-outbound JSON review, bounded HTTPS subscription/link preview, limits, cancellation, no automatic connect, and no persistent subscription URL.
- Preserve its process contract: verified pinned Xray before preflight or runtime, no direct fallback from the two loopback listeners, one session owner, serial state changes, no overlapping health checks, contained Windows child creation, bounded diagnostics and confirmed stop/cleanup before another connect.
- Store saved profile secrets using Windows DPAPI and current-user file permissions. Keep temporary runtime config private and clean it on normal exit and recovery. Display profile metadata without returning secrets to the UI.
- Use the Mini design for screen structure, typography, colors and iconography. Native window and tray affordances are platform elements. Sample design content is replaced by actual data.
- Ship the whole desktop app, runtime, Xray binary and notices. A standalone GUI executable is not a complete package.

## Testing Decisions

- Test at the highest observable seam: desktop commands and state for import, selection, connection, health, cancellation, cleanup and restart. Use fakes only for deterministic failure and race cases; use a real pinned Xray for integration.
- Keep focused parser tests for malformed/ambiguous input and trust-boundary limits. Use synthetic values; never commit personal subscriptions or credentials.
- Native Windows tests must cover DPAPI and another-account access, file ACLs, pinned binary rejection, loopback-only listeners, port conflicts, Job Object cleanup after abrupt parent exit, stale events and stop failure recovery.
- Verify RAW/REALITY, XHTTP/REALITY and XHTTP/TLS through both HTTP and SOCKS5 with the actual Windows package. The CI loopback gate and native Windows 11 interactive checks are distinct; external peer/egress checks need authorized test endpoints.
- Compare each delivered UI state with the specified Pencil frame in the running Windows app. Attach secret-free screenshots and deviations to the ticket. Build success alone is not visual acceptance.
- CI builds/tests/packages on Windows; a Windows Server runner does not substitute for Windows 11 GUI, tray, cross-user or external network acceptance.

## Out of Scope

- Windows TUN/system VPN, changing system proxy settings, kill switch, split tunneling, Windows 10, macOS and Linux builds in this parity milestone.
- Auto-update, startup at login, signing/publishing, automatic subscription refresh and extra protocol families beyond X-Ray Mini's supported set.
- Refactoring the Android UI or changing its existing VPN/storage behavior.

## Further Notes

The desktop [Pencil source](../../design/xray-desktop.pen) is a copy of the locally edited Mini file. The [desktop design handoff](../../docs/agents/desktop-design.md) maps authoritative frames. X-Ray Mini's implementation and verification documents are comparison sources, not proof that this new build passes acceptance.

Execution path: 01 → 02 → {03, 04}; 04 → {05, 06, 07, 08}; {03, 05, 06, 07, 08} → 09. Work unblocked tickets with `/implement`, checking nontrivial behavior through `/tdd`; complete each UI ticket with a running-screen Pencil comparison and evidence. Review each ticket's diff with `/code-review`, then run `/retro` after final Windows acceptance. Tickets from this spec are already `ready-for-agent` and do not need triage.
