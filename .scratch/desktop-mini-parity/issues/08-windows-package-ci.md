# 08: Package and gate the Windows app

Status: ready-for-agent
Blocked by: 04 (Connect through a contained Xray process)

**What to build:** A distributor can produce and verify a complete Windows desktop artifact containing the app, its Java runtime, the pinned Xray binary and required notices.

## Acceptance criteria

- [ ] A reproducible command builds the Windows 11 x64 installer or archive; missing, extra or modified required runtime files fail package verification.
- [ ] CI runs desktop tests, builds the package and exercises real Xray loopback for RAW/REALITY, XHTTP/REALITY and XHTTP/TLS through both HTTP and SOCKS5.
- [ ] The artifact records source revision and hashes, omits user profiles/credentials, and includes license and quick-start information.
- [ ] CI reports exactly which gates ran and does not equate a Windows Server build with Windows 11 interactive acceptance.

## Comments
