# WorkflowGuard 0.3.3

WorkflowGuard `0.3.3` is a publication-hardening release for the state-aware
Burp Suite workflow mutation extension.

## Security and reliability

- Revalidates the raw HTTP method, effective rendered target, Host header, Burp
  scope, and state-change classification immediately before every send.
- Binds configured actor credentials to one origin and blocks cross-origin
  credential reuse.
- Uses linear-time RE2/J extraction patterns and rejects unsafe, oversized, or
  control-character variable values.
- Applies structural and size limits to workflow archives, persisted project
  data, responses, generated cases, request counts, and domain fields.
- Exports conservative evidence without HTTP bodies, sensitive headers, cookie
  names, query values, identifiers, or extracted variable values.
- Retains complete HTTP evidence only for the latest run; older result rows are
  bounded summaries.

## Supply chain

- Locks Gradle dependencies and verifies the Gradle distribution checksum.
- Pins GitHub Actions to immutable commit identifiers.
- Bundles Jackson and RE2/J with their notices and licenses.
- Keeps the Burp-provided Montoya API out of the distributable JAR.

## Compatibility

- Burp Suite Professional and Community
- Montoya API `2026.7`
- Java 21
- Offline operation with no telemetry or cloud dependency

## Validation

- 88 tests, zero failures, zero errors, one opt-in real-laboratory test skipped.
- Direct UI regression campaign on Burp Suite Community Edition `2026.7.1`.
- Final JAR inventory: 1,446 entries, no duplicates, no Montoya API classes.
- Gitleaks: zero findings in reachable history and publishable files.
- OSV runtime-dependency query: zero known vulnerabilities on 2026-07-31.

- Artifact: `workflowguard-0.3.3.jar`
- Size: 2,852,241 bytes
- SHA-256:
  `8FB9CD29EC79CE5B9489A55F7BCAC321393E7B4426CE63BD84A0B9E5D29955DD`

See the [README](../README.md), [Community validation
report](community-validation-20260731.md), and [BApp readiness
matrix](bapp-readiness.md) for installation and evidence.
