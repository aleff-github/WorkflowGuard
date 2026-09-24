# BApp Store publication readiness

Assessment date: 2026-09-24

PortSwigger criteria revision checked: 2026-09-22

## Verdict

WorkflowGuard `0.3.3` continues to satisfy the current technical BApp Store
acceptance criteria. The source repository is public and suitable for
PortSwigger review.

Submission is not recorded as complete in this repository. The repository owner
must personally accept the legal confirmations in PortSwigger's
extension-portal issue form.

## Acceptance-criteria matrix

| # | PortSwigger criterion | WorkflowGuard evidence | Status |
| --- | --- | --- | --- |
| 1 | Unique function | Generates controlled multi-step state mutations and evaluates explicit probes, invariants, and cleanup; this differs from Sequence Comparer, AuthMatrix, Autorize, Auth Analyzer, and API Workflow Manager, which focus on comparison, authorization replay, or endpoint organization rather than mutation of complete stateful workflows with explicit before/after state assertions. | Pass |
| 2 | Clear name and description | `WorkflowGuard`, one-line summary, detailed overview, features, and usage text are prepared in [bapp-submission.md](bapp-submission.md). | Pass |
| 3 | Secure operation | Untrusted request messages are validated before storage and again after rendering. The modeled method, raw method, effective target, Host, scope, request count, origin-bound credentials, extracted values, and state-change confirmation are all enforced. | Pass |
| 4 | All dependencies included | Jackson and RE2/J runtime dependencies, project license, third-party notices, and dependency licenses are embedded in the release JAR. Montoya remains `compileOnly` because Burp provides it. | Pass |
| 5 | Background threads and error reporting | Replay uses a single background executor; completion returns to the Swing event thread; unexpected failures are written to Burp's extension error stream. | Pass |
| 6 | Clean unload | An unloading handler closes the tab runtime, removes the workflow listener, and calls `shutdownNow()` on the execution executor; Burp removes the API registrations it owns. | Pass |
| 7 | Burp networking | Target requests use Montoya `Http.sendRequest()`; the extension does not use a separate HTTP client. | Pass |
| 8 | Offline operation | No telemetry, cloud service, online definitions, or external runtime service is required. | Pass |
| 9 | Large-project behavior | Only explicitly selected requests are mapped; the extension never enumerates full Proxy history or Site map data. Imports, responses, generated cases, request counts, visible run history, and retained full HTTP evidence are bounded. | Pass |
| 10 | Parent GUI elements | Dialogs and file choosers use `SwingUtils.suiteFrame()` supplied by the Burp bootstrap; a unit test covers the injected parent. | Pass |
| 11 | Montoya API artifact | Gradle references `net.portswigger.burp.extensions:montoya-api:2026.7`; the provided API classes are absent from the JAR. | Pass |
| 12 | Burp AI default provider | Not applicable: WorkflowGuard has no AI functionality or third-party AI provider. | N/A |

## Release evidence

- Clean JDK 21 build:
  `.\gradlew.bat clean test jar --no-daemon --rerun-tasks`.
- Automated baseline: **88 tests**, zero failures, zero errors, one opt-in
  credential-dependent laboratory test skipped, across 26 test suites.
- Burp Suite Community Edition `2026.7.1`: direct `0.3.3` UI validation
  confirmed load, portable workflow import, mutation generation, unsafe raw
  method rejection, out-of-scope execution blocking, credential-storage
  disclosure, and portable-export disclosure.
- The earlier full Community campaign additionally covered authorized
  loopback execution, invariant failure, cleanup verification, evidence export,
  issue submission behavior, and unload/reload.
- The 2026-07-31 validation build recorded a JAR size of **2,852,241 bytes**
  and SHA-256
  `8FB9CD29EC79CE5B9489A55F7BCAC321393E7B4426CE63BD84A0B9E5D29955DD`.
  The binary currently attached to the GitHub `v0.3.3` release is a different
  build; see [release integrity](release-integrity.md) before using a checksum
  for verification.
- The validated JAR inventory contained 1,446 entries, no duplicate entries,
  no bundled Montoya classes, and all expected project/dependency notices.
- Full reachable Git history and publishable files: zero Gitleaks findings.
- OSV query for all bundled runtime components: zero known vulnerabilities on
  the assessment date.
- Curated screenshots: 28 files, no exact duplicates, reviewed for account
  details, credentials, local desktop content, tokens, and cookies.

The exact final verification commands and UI observations are recorded in
[community-validation-20260731.md](community-validation-20260731.md).

## Compatibility freshness

- PortSwigger's BApp Store acceptance criteria and submission guidance were
  rechecked on 2026-09-24 against documentation updated 2026-09-22.
- The latest Burp Suite Professional / Community release at this review is
  `2026.9`, published 2026-09-21.
- Maven Central currently lists Montoya API `2026.7`, which remains the
  compile-time API used by WorkflowGuard.
- Direct runtime/UI validation evidence in this repository remains against
  Burp Suite Community Edition `2026.7.1`. This document does not claim a
  completed direct `2026.9` regression campaign.

## Submission fields

The current PortSwigger submission process requires an accessible GitHub
repository containing the relevant source, a clear name and description,
usage/setup information, and a new extension-submission issue. Ready-to-paste
values are in [bapp-submission.md](bapp-submission.md).

Official references:

- [BApp Store acceptance criteria](https://portswigger.net/burp/documentation/desktop/extend-burp/extensions/creating/bapp-store-acceptance-criteria)
- [Submitting extensions](https://portswigger.net/burp/documentation/desktop/extend-burp/extensions/creating/bapp-store-submitting-extensions)
- [Extension submission portal](https://github.com/PortSwigger/extension-portal/issues/new/choose)
