# 04: Connect through a contained Xray process

Status: ready-for-agent
Blocked by: 02 (Import and manage protected profiles)

**What to build:** The user connects a saved profile and gets working local SOCKS5/HTTP endpoints, can disconnect, and sees process state and network health separately.

**Platform direction (2026-11-05):** The user changed the initial target to macOS on Apple Silicon; Windows implementation and checks are deferred. Replace the Windows Job Object criterion with macOS child-process containment and confirmed cleanup. See ADR 0003.

## Acceptance criteria

- [ ] Only the pinned, digest-checked Xray runs for preflight and service; temporary runtime config is current-user private.
- [ ] The child is created inside a kill-on-close Windows Job Object. One owner waits for it and confirms cleanup before another Connect.
- [ ] SOCKS5 binds only `127.0.0.1:10808`, HTTP only `127.0.0.1:10809`; both route through selected VLESS without an implicit direct fallback.
- [ ] A real HTTP request through the proxy establishes readiness; process running, check failure and connected status remain distinct. Periodic checks do not overlap.
- [ ] Home states match `Ldm2t` and `xMtM8` with actual profile/process/check values. Tests cover real local HTTP and SOCKS5 traffic, occupied ports, modified runtime and normal disconnect.

## Comments

- 2026-11-05: macOS Apple Silicon implementation uses the pinned Xray 26.10.31 executable, a per-session private configuration, loopback-only listeners and a native process supervisor. A local synthetic VLESS server test verifies HTTP and SOCKS5 traffic, occupied ports, normal shutdown and stale-file recovery. Supervisor tests cover parent death; session tests cover startup cancellation, timeouts and stop retry.
- 2026-11-05: Compared running Compose screens with Pencil frames `Ldm2t` and `xMtM8` using synthetic profile and process state. Secret-free evidence: `evidence-04/disconnected-macos.png` and `evidence-04/connected-macos.png`. Process and health values replace design sample data. The native macOS title bar is platform-provided.
- Windows criteria and checks are deferred at the user's direction. A real provider connection and its network health remain unverified.
- 2026-11-05: After the user confirmed the Keychain prompt and unlocked the Mac, the packaged app opened to its disconnected Home screen. The connected-state visual comparison uses the synthetic Compose harness, and local traffic uses the synthetic VLESS integration test.
