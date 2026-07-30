# Burp Suite Community validation — 2026-07-31

## Outcome

WorkflowGuard `0.3.3` passed its release-candidate security and packaging
campaign. The build completed from a clean tree output directory, the complete
automated suite passed, and the extension was exercised directly through the
Burp Suite Community Edition user interface.

The UI campaign confirmed that preview generation does not send traffic and
that the new trust-boundary controls fail closed. An edited raw request whose
method diverged from its modeled step was rejected on save, and a generated
case targeting an origin outside Burp scope was blocked before transmission.
The actor and export dialogs also disclosed their storage and best-effort
redaction boundaries.

## Environment

| Component | Value |
| --- | --- |
| Operating system | Windows 11 |
| Burp Suite | Community Edition `2026.7.1` |
| WorkflowGuard | `0.3.3` |
| Montoya API | `2026.7` |
| Java build toolchain | Eclipse Temurin `21.0.12+8` |
| UI target | Authorized local Juice Shop origin |
| UI execution policy | In-scope only |

The validation used an isolated temporary Burp data directory. A separate
user-owned Burp laboratory session was already listening on the default proxy
port, so the isolated instance recorded two Burp-owned Target/Proxy
initialization events for that port conflict. The developer extension itself
was discovered, its suite tab appeared, and no WorkflowGuard exception was
written to standard output or the extension error path.

## Automated baseline

The final source state was rebuilt with:

```powershell
.\gradlew.bat clean test jar --no-daemon --rerun-tasks
```

Result: **88 tests**, **0 failures**, **0 errors**, **1 skipped** across 26 test
suites. The skipped test is the opt-in credential-dependent real-laboratory
integration guard; self-contained fixture and execution-coordinator integration
tests ran successfully.

Release artifact:

```text
build/libs/workflowguard-0.3.3.jar
Size 2,852,241 bytes
SHA-256 8FB9CD29EC79CE5B9489A55F7BCAC321393E7B4426CE63BD84A0B9E5D29955DD
```

JAR inspection found 1,446 entries, zero duplicate entries, zero bundled
Montoya API classes, and the expected WorkflowGuard, Jackson, and RE2/J
licenses/notices. A byte-identical copy of this final artifact was then loaded
in a fresh temporary Community project; Burp displayed the WorkflowGuard suite
tab and no WorkflowGuard exception appeared in the captured output.

## Direct UI regression campaign

| Check | Observed result |
| --- | --- |
| Java extension load | Successful; WorkflowGuard suite tab visible |
| Portable workflow import | Successful; sanitized Juice Shop catalog workflow loaded |
| Mutation preview | Three deterministic cases generated; no preview traffic sent |
| Modeled/raw method mismatch | `DELETE` raw template rejected for a modeled `GET` step |
| Template restoration | Original `GET` template restored and saved |
| Scope safety gate | Generated case reported `BLOCKED`; no request sent |
| Actor credential dialog | Cookie/authorization fields masked; project-storage disclosure visible |
| Portable export dialog | Best-effort redaction and manual-review warning visible |
| Retention disclosure | UI states that only the latest run retains full HTTP evidence |

The imported archive is the sanitized fixture already tracked under
`docs/evidence/ui-validation-20260730/`. No credential file or live account
secret was imported into the release repository.

## Curated screenshots

- [Credential storage warning](screenshots/ui-community-20260731/01-credential-storage-warning.png)
- [Unsafe request-method rejection](screenshots/ui-community-20260731/02-request-method-safety-block.png)
- [Out-of-scope run blocked](screenshots/ui-community-20260731/03-out-of-scope-run-block.png)
- [Portable export warning](screenshots/ui-community-20260731/04-portable-export-warning.png)

The four new images contain only Burp, a local loopback target, sanitized
workflow data, and empty credential fields. They were visually reviewed and
checked against the existing screenshot set for exact duplicates.

## Relationship to the full Community campaign

This `0.3.3` pass targets the hardening introduced after `0.3.2`. The complete
end-to-end Community scenario—including authorized execution against the
vulnerable fixture, dynamic extraction, invariant failure, cleanup restoration,
redacted evidence export, audit-issue API behavior, and unload/reload—is
preserved in [the 2026-07-30 report](community-validation-20260730.md).

Together, the two campaigns cover both the full feature lifecycle and the
new `0.3.3` safety boundaries.
