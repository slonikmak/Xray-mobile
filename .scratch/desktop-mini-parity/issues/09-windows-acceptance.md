# 09: Verify desktop parity on Windows 11

Status: ready-for-agent
Blocked by: 03 (Preview and save a subscription profile), 05 (Cancel, recover and retry safely), 06 (Keep the session in the tray), 07 (Show truthful details and diagnostics), 08 (Package and gate the Windows app)

**What to build:** A reviewer can run the complete packaged app on Windows 11 and demonstrate the Mini behavior and design with recorded, secret-free evidence.

## Acceptance criteria

- [ ] Complete a parity checklist for JSON and subscription import, profile operations, all connection states, tray, details, diagnostics and shutdown against Mini commit `c22f052`.
- [ ] On a normal Windows 11 x64 account, verify the packaged GUI, keyboard/scaling, six primary Pencil frames, listener binding, port conflicts and crash cleanup.
- [ ] Verify current-user DPAPI/ACL protections with a second account and real core/loopback transports; record external peer/egress results only when authorized endpoints are available.
- [ ] Record exact app revision, artifact hashes, Windows build, commands, PASS/FAIL/SKIP and remaining limits. Do not mark the milestone complete while required parity checks remain unverified.

## Comments
