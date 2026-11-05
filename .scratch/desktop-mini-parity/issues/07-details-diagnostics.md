# 07: Show truthful details and diagnostics

Status: ready-for-agent
Blocked by: 04 (Connect through a contained Xray process)

**What to build:** The user sees the local endpoint addresses, the active and selected profiles, pinned runtime information, and bounded, secret-free recent activity.

## Acceptance criteria

- [ ] Details copies SOCKS5/HTTP addresses and shows the bundled core version and verification status from real package metadata.
- [ ] Diagnostics distinguishes process state from network health, provides useful fixed error categories and never retains raw credentials, source URLs or Xray output.
- [ ] The bounded log remains responsive under output flood and refreshes after reopening the window.
- [ ] Running screens match Pencil frames `tEbx1` and `s2agP9`, including long entries, keyboard navigation and large text.
- [ ] Tests verify redaction, bounded history and accurate metadata/status after failure and recovery.

## Comments
