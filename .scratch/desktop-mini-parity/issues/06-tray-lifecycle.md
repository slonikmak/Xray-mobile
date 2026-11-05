# 06: Keep the session in the tray

Status: ready-for-agent
Blocked by: 04 (Connect through a contained Xray process)

**What to build:** Closing the window hides it while an active local proxy continues; the user can reopen the same session and exit through the tray with owned Xray resources stopped.

**Platform direction (2026-11-05):** Implement and verify on macOS first; Windows work and checks are deferred by the user.

## Acceptance criteria

- [ ] Tray icon and menu show disconnected, healthy and attention states without claiming health from process presence alone.
- [ ] Open returns to current authoritative state, including a session started before the window was reopened.
- [ ] Cancel startup, Disconnect or Retry stop remains available from the tray when relevant; Exit waits for confirmed cleanup.
- [ ] Native Windows checks cover close/reopen, ordinary Exit and forced GUI termination with no orphan Xray child.

## Comments

- 2026-11-05: Implemented macOS tray status, Open, stop/cancel/retry and Exit. Window close preserves the session; native Quit waits for recovery and shutdown. If recovery remains unavailable the user sees its unconfirmed state and can explicitly exit without it. Desktop lifecycle and serialized session-command tests cover hide/reopen, unavailable tray, startup/exit races and failed-stop retry. Full build passed; native tray interaction is still being checked.
- 2026-11-05: The user requested a quick build and basic check instead of detailed verification, then paused work. The packaged app opened; closing its window left the process running, and ⌘Q exited it. Menu interaction and Dock reopen after the latest change were not checked. See [STATUS.md](../STATUS.md).
