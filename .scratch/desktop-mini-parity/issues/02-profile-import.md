# 02: Import and manage protected profiles

Status: ready-for-agent
Blocked by: 01 (Launch the Compact desktop app)

**What to build:** The user imports a supported Xray JSON profile, reviews what will be discarded, and can select, rename, delete and recover multiple saved profiles after restart.

## Acceptance criteria

- [ ] Import accepts only the supported single VLESS outbound and explicitly reviews dropped sections; malformed, ambiguous or unsafe input is rejected before save.
- [ ] Names and selection persist; profile secrets use Windows DPAPI and current-user file permissions, and are absent from UI snapshots and logs.
- [ ] Rename and delete work, with a clear destructive confirmation and no stale selection after deletion.
- [ ] The Import and Profiles screens match Pencil frames `RiHqd` and `E5EIx` with real data, including long names, keyboard use and error states.
- [ ] Deterministic command-level tests cover import boundaries, restart, rename, delete and storage errors; native Windows checks cover DPAPI/ACL behavior.

## Comments


- 2026-11-05: Proceeding under the user’s explicit instruction to skip Windows checks. DPAPI/ACL protections remain implementation requirements; native verification is not claimed.
- 2026-11-05: Compared Import and Profiles against Pencil on macOS with a one-off Compose UI harness and synthetic in-memory data. Secret-free captures are in evidence-02; the JSON field is intentionally masked, import is two-step to review discarded sections, and native title bars are platform-owned. Windows and DPAPI/ACL verification were skipped at the user’s request.
- 2026-11-05: Implemented JSON review and profile management. Standards/Spec review identified a 2 MiB restore boundary and hidden dialog errors; both were fixed and regression-tested. The reviewer confirmed Mini defers protocol-specific validation to Xray. The user waived Windows checks; DPAPI/ACL behavior remains unverified.
