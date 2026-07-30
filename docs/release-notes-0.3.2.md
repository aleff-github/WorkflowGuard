# WorkflowGuard 0.3.2

WorkflowGuard is a state-aware Burp Suite extension for controlled mutation of
multi-step HTTP workflows.

## Highlights

- Deterministic skip, repeat, replay-earlier, actor-swap, and stale-value cases.
- Explicit action, probe, and cleanup phases with before/after JSON invariants.
- Per-run actor isolation, cookie updates, and dynamic value extraction.
- Scope enforcement, request caps, and confirmation before state changes.
- Redacted workflow and evidence exports.
- Informational Burp issue publication for explicit invariant failures.
- Burp Community-aware messaging and clean extension unload.

## Compatibility

- Burp Suite Professional and Community
- Montoya API `2026.7`
- Java 21
- Offline operation with no telemetry or cloud dependency

## Validation

- 70 tests, zero failures, zero errors, one opt-in real-laboratory test skipped.
- Final JAR loaded directly in Burp Suite Community Edition `2026.7.1`.
- Linux CI build and artifact publication verified.
- Gitleaks scan: zero findings.
- OSV runtime-dependency query: zero known vulnerabilities.

- Artifact: `workflowguard-0.3.2.jar`
- Size: 2,717,280 bytes
- SHA-256:
  `C7298C8ECE9DF66164F9DDC03CDC1ECB95D8A8F83E5B46C293561940ABD6297C`

See the [README](../README.md), [Community validation
report](community-validation-20260730.md), and [BApp readiness
matrix](bapp-readiness.md) for installation and evidence.
