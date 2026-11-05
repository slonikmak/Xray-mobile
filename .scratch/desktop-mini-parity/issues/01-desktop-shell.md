# 01: Launch the Compact desktop app

Status: ready-for-agent
Blocked by: None (can start immediately)

**What to build:** A Windows user opens this project's desktop app and sees the real disconnected state, empty profile list and navigation in the Mini Compact visual system. Unavailable actions stay honest until their backing behavior exists.

## Acceptance criteria

- [ ] A desktop application builds and starts as a Windows 11 x64 normal user; it uses the existing shared JVM target without changing Android behavior.
- [ ] Home, Profiles and Details navigation works from an empty state; no mock profile, running process or successful check is shown.
- [ ] Window controls, 360 × 600 Compact layout, larger window, keyboard focus and accessible labels work.
- [ ] Running screenshots are compared with Pencil frames `Ldm2t`, `E5EIx` and `tEbx1`; differences and commands are recorded without secrets.

## Comments


- 2026-11-05: Initial desktop shell added. `./gradlew :desktop:desktopTest` checks navigation and empty states; `./gradlew build` includes Android and desktop checks. On macOS, Home, Profiles and Details were opened and compared with frames Ldm2t, E5EIx and tEbx1. Empty-state text replaces sample profiles, endpoints and runtime; native window controls differ by platform. Windows 11 launch, keyboard/scaling, larger window and secret-free screenshot evidence remain to be checked; the ticket is not complete.
- 2026-11-05 review: `./gradlew :desktop:createDistributable` built the macOS app and its Home, Profiles and Details screens were inspected against Ldm2t, E5EIx and tEbx1. The native title bar differs by OS; absent profiles, endpoints, core version and actions are represented as empty or disabled. Review found glyph and state-grid differences; these were corrected in code. macOS inspection is preliminary, not Windows evidence.
- 2026-11-05: Standards and Spec reviews against 359e7e0 identified the icon and state-grid differences; both were corrected and re-reviewed. `./gradlew build :desktop:createDistributable` passed, and the updated three screens were inspected on macOS. Windows 11 checks and screenshot evidence remain outstanding.
- 2026-11-05: The user explicitly chose to skip Windows checks and proceed to the next tickets. Windows launch, scaling and screenshot acceptance remain unverified; this exception unblocks ticket 02.
