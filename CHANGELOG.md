# Changelog

## 0.3.4 - 2026-09-24

### Security

- Update Jackson Databind and the aligned Jackson runtime components to
  `2.22.3`, which includes fixes for CVE-2026-91776 and CVE-2026-91777.
- Verify the distributable JAR in CI, reject bundled Montoya API classes and
  duplicate entries, and generate a SHA-256 file from the exact artifact.

### Changed

- Add a tag-driven release workflow that requires the tag version to match the
  Gradle project version and publishes the same verified JAR/checksum pair.
- Document the `0.3.3` validation-build versus release-asset checksum
  discrepancy so historical evidence is not mistaken for the attached binary.
- Refresh the BApp Store readiness review against PortSwigger's
  2026-09-22 acceptance criteria.

## 0.3.3 - 2026-07-31

### Security

- Bind configured actor cookies and authorization seeds to one HTTP origin and
  block plans that assign those credentials across origins.
- Derive scope and evidence URLs from the rendered request target, while
  rejecting modeled-method, Host, and absolute-target divergence before send.
- Reject control characters and oversized extracted values before template
  substitution; evaluate extraction patterns with the linear-time RE2/J engine.
- Make evidence export conservative by omitting HTTP bodies, non-essential
  header values, query values, identifiers, and all extracted variable values.
- Validate and size-bound imported workflows, persisted project data, raw
  responses, domain fields, and absolute per-run request counts.

### Changed

- Retain full HTTP evidence only for the latest in-session run and compact the
  older result-matrix entries.
- Lock dependency versions, verify the Gradle distribution checksum, pin GitHub
  Actions to immutable commits, and include RE2/J licensing in the JAR.

### Validated

- Added adversarial regressions for method/Host divergence, rendered-target
  scope changes, cross-origin credentials, CRLF extraction, regex syntax,
  conservative redaction, oversized imports/responses, and evidence compaction.
- Rebuilt with 88 tests, 0 failures, 0 errors, and one opt-in real-laboratory
  test skipped; repeated the safety-critical UI checks on Burp Suite Community
  Edition `2026.7.1`.

## 0.3.2 - 2026-07-30

### Changed

- Clarify Burp Community behavior for audit-issue publication and label the
  Professional-only issue viewer directly in the UI.
- Publish a curated, privacy-reviewed screenshot and evidence set.
- Embed the WorkflowGuard GPLv3 license in the distributable JAR.

### Fixed

- Report unexpected background execution failures to Burp's extension error
  stream while retaining the user-facing failure message.
- Parent every extension dialog and file chooser to Burp's main suite frame.
- Make the Gradle wrapper executable in Linux CI.

### Validated

- Rebuilt the release with 70 tests, 0 failures, 0 errors, and one opt-in
  real-laboratory test skipped by the default run.
- Repeated the direct Burp Suite Community Edition load, execution, export, and
  unload/reload campaign against the packaging-equivalent release candidate.
- Loaded the final `0.3.2` JAR in Community and confirmed successful
  initialization, expected registrations, Low system impact, and an empty
  extension error stream.

## 0.3.1 - 2026-07-30

### Fixed

- Preserve valid CRLF request framing when requests are edited or sent from the
  Swing interface.
- Centralize evidence redaction so workflow archives and run evidence use the
  same sensitive-data policy.
- Redact e-mail addresses and common personal-data fields from JSON bodies,
  form data, query strings, variables, and invariant evidence.

### Validated

- Exercised ten generated mutation cases through the WorkflowGuard Burp user
  interface against local OWASP Juice Shop and crAPI laboratories.
- Replayed isolated actors through the production execution coordinator and
  the Burp loopback proxy.
- Completed independent AGY and Codex reviews of sanitized evidence.
- Verified the post-fix UI export contains no live bearer value, e-mail
  address, or password value.
