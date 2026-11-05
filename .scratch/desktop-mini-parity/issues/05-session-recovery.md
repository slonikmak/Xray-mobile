# 05: Cancel, recover and retry safely

Status: ready-for-agent
Blocked by: 04 (Connect through a contained Xray process)

**What to build:** The user can cancel startup, survive Xray failure or network loss, retry a failed stop, and reconnect only after the previous session has really released its resources.

## Acceptance criteria

- [ ] Late events from an exited or previous session cannot restore a false connected state; selected and active profiles remain distinct.
- [ ] Cancel during preflight/start/check and repeated Connect/Disconnect do not leak a child, ports or temporary config.
- [ ] Failed stop remains visible with Retry stop; success is shown only after Wait and cleanup. Restart recovers stale private runtime files without disturbing another live instance.
- [ ] Health degradation and recovery follow Mini's two-failure policy without automatically restarting Xray.
- [ ] Tests cover race ordering, stop refusal, abrupt parent death and recovery on native Windows; UI error/retry states are visually checked against the Compact system.

## Comments

- 2026-11-05: macOS-first direction supersedes native Windows checks. Added cancellation handoff, confirmed cleanup before reconnect, timeout/retry handling, automatic stale-file recovery, and two-consecutive-failure health degradation with recovery. Native supervisor tests exercise abrupt parent exit; session tests use controlled process and network checks. The remaining recovery and UI acceptance needs a complete macOS review before closing this ticket.
