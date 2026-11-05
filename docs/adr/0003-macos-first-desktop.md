# Build the desktop client for macOS first

The user is developing on an Apple Silicon Mac and has deferred Windows. The immediate desktop target is macOS; its profile protection and process lifecycle use macOS facilities while retaining the local proxy contract. This supersedes the Windows-first target in ADR 0002 without changing Android VPN behavior.
