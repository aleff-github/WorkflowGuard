# Burp Suite Community validation — 2026-07-30

## Outcome

The release-candidate runtime promoted to WorkflowGuard `0.3.2` passed the
direct Burp Suite Community Edition campaign. It generated mutations without
sending preview traffic, enforced its safety gates, executed the state-aware
replay scenario, detected the state violation, verified cleanup, exported
redacted evidence, and survived an extension unload/reload within the same
Community project session. The final `0.3.2` JAR was then rebuilt from that
runtime with the release metadata, embedded GPLv3 license, and background error
reporting hardening. That exact final artifact was manually loaded in the same
Burp installation: the extension initialized successfully, registered one suite
tab, one context-menu provider, and one extension-state listener, reported
**Low** system impact, and produced no extension errors.

The only edition limitation observed is owned by Burp: Community accepts an
informational audit issue through the Montoya Site map API, but its **All
issues** viewer and persistent project files are Professional-only. WorkflowGuard
now detects Community and states this in the execution controls and completion
message.

## Environment

| Component | Value |
| --- | --- |
| Operating system | Windows 11 |
| Burp Suite | Community Edition `2026.7.1` |
| WorkflowGuard | `0.3.2` |
| Montoya API | `2026.7` |
| Java toolchain | JDK `21.0.10` |
| Target | Loopback fixture, vulnerable mode |
| Target origin | `http://127.0.0.1:18080` |
| Burp proxy | `127.0.0.1:8080` |

All mutated traffic was confined to the loopback fixture. The fixture was reset
before each execution.

## Automated baseline

The final source state was rebuilt with:

```powershell
.\gradlew.bat clean test jar --no-daemon
```

Result: **70 tests**, **0 failures**, **0 errors**, **1 skipped** across 26 test
suites. The skipped test is the credential-dependent real-lab integration
guard; the self-contained fixture integration tests ran successfully.

Release artifact:

```text
build/libs/workflowguard-0.3.2.jar
Size 2,717,280 bytes
SHA-256 C7298C8ECE9DF66164F9DDC03CDC1ECB95D8A8F83E5B46C293561940ABD6297C
```

## UI campaign

| Check | Observed result |
| --- | --- |
| Final `0.3.2` Java extension load | Successful; no errors; one suite tab, one context-menu provider, and one extension-state listener registered |
| Estimated Burp system impact | Low |
| Workflow import | Successful: 5 steps, 1 actor, 1 variable, 1 invariant |
| Mutation preview | 11 deterministic cases generated; no requests sent |
| State-changing confirmation | Explicit eight-request plan shown before authorization |
| Scope safety gate | `BLOCKED`; 0 requests sent; out-of-scope reason shown |
| Authorized loopback execution | `COMPLETED`; 8 sequential requests |
| Mutation HTTP statuses | `201 → 200 → 204 → 409` |
| Dynamic extraction | `invitationId` extracted and substituted |
| Post-mutation invariant | Failed as expected because `/count` changed |
| Post-cleanup invariant | Passed; cleanup violations: 0 |
| Assessment | `MUTATION_VIOLATION_RESTORED` |
| Final fixture state | `count: 0`, no members |
| Burp issue publication | One informational issue accepted by the API |
| Community issue viewer | **All issues [Pro Only]**; limitation now disclosed in WorkflowGuard |
| Evidence export | Schema 1, `redactionApplied: true`, 8 exchanges, 2 invariant checks |
| Workflow export/import | Portable export excludes secrets by default and re-imports successfully |
| Unload/reload | Clean; suite tab removed and restored |
| Project-session persistence | Imported workflow remained after unload/reload |

The completed run took 180 ms with the inter-request delay set to zero. The
HTTP `409` response did not hide the vulnerable state transition: WorkflowGuard
detected the changed member count and then independently verified that cleanup
restored the original state.

## Screenshot set

- [Imported five-step workflow](screenshots/ui-community-20260730/03-imported-workflow.png)
- [Eleven generated mutations](screenshots/ui-community-20260730/04-generated-mutations.png)
- [Scope safety gate](screenshots/ui-community-20260730/05-scope-safety-gate.png)
- [Completed result matrix](screenshots/ui-community-20260730/06-result-matrix.png)
- [Mutation failure and cleanup pass](screenshots/ui-community-20260730/07-invariant-cleanup.png)
- [Community All issues limitation](screenshots/ui-community-20260730/08-community-all-issues-pro-only.png)
- [Suite tab after manual load](screenshots/ui-community-20260730/10-manual-load-suite-tab.png)
- [Workflow retained after unload/reload](screenshots/ui-community-20260730/11-persistence-after-reload.png)

The public set contains only maximized Burp windows and was reviewed for local
paths, desktop content, account names, credentials, tokens, and cookies.

## Release interpretation

This campaign clears the Community runtime and final-artifact
manual-installation checks. A Professional validation pass is still useful
before BApp submission to visually confirm the informational issue inside **All
issues** and to verify persistence across a saved `.burp` project. Those
Professional-only checks do not block Community use of WorkflowGuard's own
result matrix and exports.
