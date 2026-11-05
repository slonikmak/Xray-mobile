# Desktop Pencil design handoff

The desktop visual source is [xray-desktop.pen](../../design/xray-desktop.pen), copied from X-Ray Mini's `design/xray-mini.pen` on 2026-11-05. The source file had local edits; the copied bytes match SHA-256 `25c04815edbd1f7b5dc2fa26fd5b3c0900ffdf00f1e0b5e307edd19ab7d52837`. The desktop app's behavior follows X-Ray Mini at commit `c22f052`; the Pencil file governs appearance. Keep the mobile design in its own file.

Read this document and inspect the relevant frames through Pencil before each desktop UI change. The source file is a design reference, not application code. Resolve variables and instances, and use screenshots at readable size. The primary Compact frames are 360 × 600:

| State or screen | Frame ID |
| --- | --- |
| Disconnected home | `Ldm2t` |
| Connected home | `xMtM8` |
| Profiles | `E5EIx` |
| Import | `RiHqd` |
| Diagnostics | `s2agP9` |
| Details | `tEbx1` |

The file also contains alternative Compact concepts. Use the six frames above for parity unless the user selects a different concept. Current tokens include dark `#0D1118`, mint `#81EBCD`, Inter and Cascadia Code for monospace; the icons use Lucide. Inspect the current variables rather than treating these examples as a complete style sheet. Keep the native window controls, system tray, keyboard behavior and real status semantics of X-Ray Mini.

The mock profile names, logs, addresses and version are sample content. Populate screens from actual state; the SOCKS5/HTTP addresses are valid only when the desktop adapter uses those listeners. Keep selected and active profiles distinct. A running Xray process and a successful proxy check are separate facts. The Android VPN handoff's copy adaptations do not apply to this desktop UI.

For each UI ticket, run the Windows desktop app and compare its relevant states with the Pencil frames at 360 × 600 and at a larger resizable size. Repeat the final comparison with the packaged app in ticket 09. Check typography, color, spacing, scrolling, long names, keyboard focus, text scaling and accessible labels. Save secret-free screenshots and note any deliberate deviations in ticket evidence. Compilation or a Pencil screenshot alone does not complete visual verification.
