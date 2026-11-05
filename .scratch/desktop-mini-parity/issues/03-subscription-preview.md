# 03: Preview and save a subscription profile

Status: ready-for-agent
Blocked by: 02 (Import and manage protected profiles)

**What to build:** The user explicitly loads an HTTPS subscription or VLESS link, previews candidates, chooses one and saves it as a protected profile without starting a connection.

## Acceptance criteria

- [ ] URL validation, TLS, redirect policy, response/profile/link limits and supported transport fields match Mini's import contract.
- [ ] Cancel, timeout, expiry, source change and superseded requests invalidate the preview; failed import leaves existing profiles intact.
- [ ] The source URL and unselected candidates are not persisted or exposed in diagnostics.
- [ ] The Import UI follows `RiHqd` for loading, candidate selection and error states, with no invented profiles.
- [ ] Command-level tests cover success, malformed data, limits, cancellation, stale results and no automatic connect.

## Comments


- 2026-11-05: User requested skipping Windows checks. Compared the subscription import against RiHqd using a macOS Compose UI harness with a synthetic source; screenshot in evidence-03. Source text is masked and candidate metadata replaces the JSON textarea. Native Windows checks are skipped, not reported as passed.
- 2026-11-05: Implemented bounded HTTPS/VLESS preview, single-use save, cancellation, expiry and stale-result invalidation. Local HTTPS tests cover certificate rejection, redirects and response limits; Compose tests cover selection and replacement. Standards and Spec reviews found stale UI, accessibility and parsing issues, which were fixed and re-reviewed. `./gradlew build` passed on macOS. Windows checks were skipped by user request.
