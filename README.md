# WorkflowGuard

WorkflowGuard is a state-aware workflow mutation engine for Burp Suite. It captures a legitimate multi-step HTTP process, generates controlled out-of-order variants, and is designed to detect business-logic violations by comparing application state before and after execution.

> [!IMPORTANT]
> The repository contains the complete `0.3.3` local MVP described in the roadmap. The intentionally excluded items remain listed under **Explicitly out of the MVP** in the roadmap.

## What makes WorkflowGuard different

WorkflowGuard focuses on state transitions across complete business workflows,
rather than treating requests as independent test cases:

| Related BApp category | Primary focus | WorkflowGuard difference |
| --- | --- | --- |
| Sequence comparison | Compare two captured request/response sequences | Generates and executes controlled ordering, replay, repetition, actor-swap, and stale-value mutations. |
| Authorization matrices and request replay | Compare individual requests across users or roles | Maintains isolated actor sessions across multi-step flows and tests authorization together with workflow state. |
| API workflow organization | Inventory, label, and export endpoints | Executes workflows and evaluates before/after probes, invariants, dynamic variables, and cleanup verification. |

This makes WorkflowGuard complementary to tools such as
[Sequence Comparer](https://portswigger.net/bappstore/fd75bc61c9364dab833a61312fdaa07d),
[AuthMatrix](https://portswigger.net/bappstore/30d8ee9f40c041b0bfec67441aad158e),
and [API Workflow Manager](https://portswigger.net/bappstore/24e3497855a44575a38302f540108e98):
the extension is intended to answer whether an unexpected transition changed
application state, why it did so, and whether cleanup restored the expected
state.

## Implemented foundation

- Java 21 extension based on the Montoya API.
- “Add to active workflow” action in Burp context menus.
- Swing suite tab with workflow CRUD, actor and step assignment, editable request templates, variables, invariants, generated cases, and run evidence.
- Immutable domain model with project-backed JSON persistence and an in-memory fallback.
- Deterministic `skip`, `repeat`, `replay earlier step`, `swap actor`, and configured stale object/token mutations.
- State-aware presets for replay after revocation, replay after deletion, and repeated one-time use.
- Per-run, per-origin actor sessions with isolated cookie jars, `Set-Cookie` updates, and optional masked `Authorization` seeds. Explicit credentials are bound to one origin and a run is blocked if that actor spans origins.
- Captured authorization is replaced per configured actor and removed for custom actors without an authorization seed.
- Linear-time RE2/J regex and validated JSON Pointer variable extraction.
- Strict `${variable}` request-template substitution that rejects control characters and oversized values.
- Automatic UTF-8 `Content-Length` recalculation after template substitution.
- Semantic JSON diff with ignored fields and optional array-order normalization.
- JSON invariants for unchanged state, absence, literal equality, array size, forbidden values, and safe restricted expressions.
- Workflow-level volatile JSON Pointer configuration for `UNCHANGED` comparisons.
- Request-count, scope, state-changing confirmation, modeled-method, effective-target, and Host/origin safety gates.
- Sequential Montoya request execution on a bounded background worker.
- Per-run request cap, inter-request delay, live Burp scope revalidation, and explicit confirmation.
- Immutable run/step results with raw request and response evidence in the suite tab.
- In-session result matrix comparing actors, mutation HTTP outcomes, invariant failures, cleanup restoration, and execution duration across up to 50 runs. Full HTTP evidence is retained only for the latest run; older rows are compact summaries.
- Interactive workflow graph with separate action, probe, and cleanup lanes.
- Explainable dependency suggestions based on variable consumption, resource lifecycle, and observed request order.
- Conservative JSON evidence export that removes HTTP headers and bodies, response reason phrases, cookie names, all extracted values, identifiers, query strings, credentials, and recognized personal data.
- Size-bounded, structurally validated workflow import/export, with best-effort redaction by default and an explicit trusted-export option.
- Optional publication of failed post-mutation invariants as informational Burp audit issues with HTTP evidence.
- Before/after probes, invariant checks, cleanup steps, and post-cleanup probe verification.
- Unit and loopback integration tests for the core execution lifecycle.

## Requirements

- JDK 21.
- A recent Burp Suite version compatible with Montoya API `2026.7`.

The project includes the Gradle Wrapper, so a separate Gradle installation is not required.

WorkflowGuard `0.3.3` has been exercised end-to-end on Burp Suite Community
Edition `2026.7.1`. Community can load and run the extension, but Burp reserves
project files and the **All issues** viewer for Professional. WorkflowGuard
therefore keeps full run evidence in its own tab, supports redacted JSON and
portable workflow exports, and labels Burp issue publication as **Pro viewer
only** when Community is detected. Export reusable workflows before closing a
Community temporary project.

## Build and test

On Windows:

```powershell
.\gradlew.bat clean test jar
```

On Linux or macOS:

```bash
./gradlew clean test jar
```

The loadable extension is produced at:

```text
build/libs/workflowguard-0.3.3.jar
```

## Load in Burp Suite

1. Open **Extensions → Installed**.
2. Select **Add**.
3. Choose **Java** as the extension type.
4. Select the JAR from `build/libs`.
5. Open the **WorkflowGuard** suite tab.
6. In Proxy history or a message editor, right-click one or more requests and choose **WorkflowGuard → Add to active workflow**.
7. Define actor identities with an initial `Cookie`, `Authorization`, or both, then assign an actor and `ACTION`, `PROBE`, or optional `CLEANUP` role to each step. Authorization seeds are masked in the UI.
8. Configure variables and invariants as needed. A variable can carry an optional stale value; `EXPRESSION` invariants use the restricted syntax documented below.
9. Review **Workflow graph** for inferred data, lifecycle, and captured-order dependencies; select a node or suggestion to locate its captured step.
10. Generate mutations, including state-aware presets when relevant, select a case, review the actor-labelled sequence, request cap, delay, and scope restriction, then run it.
11. Compare runs in **Result matrix**. The latest row retains detailed HTTP evidence; older rows retain compact status and invariant metadata without request, response, cookie, or extracted-variable values.
12. Leave **Publish invariant failures to Burp issues** enabled to submit failed post-mutation checks as informational audit issues. In Community, the API accepts the issue but Burp's **All issues** viewer is Pro-only; review and export the evidence from WorkflowGuard instead. WorkflowGuard deliberately does not infer impact from a response difference alone.
13. Export the selected run as conservatively redacted JSON when evidence must leave Burp. HTTP start lines and structured run metadata remain, while headers, bodies, cookie names, query values, identifiers, and extracted values are omitted or masked. Use **Import** and **Export** for portable workflow files; portable workflow redaction is best effort, so review the file before sharing it. Secrets are included only when explicitly requested through trusted export.

Burp discovers the public `Extension` bootstrap class, which delegates immediately to the packaged application code.

## Project layout

```text
├── src/main/java/
│   ├── Extension.java                     Burp bootstrap
│   └── dev/workflowguard/
│       ├── application/                   use cases
│       ├── core/                          mutation, diff, invariant, and safety engines
│       ├── domain/                        immutable domain objects
│       ├── ports/                         repository and document-store boundaries
│       └── adapters/
│           ├── burp/                      Montoya integration
│           ├── persistence/               JSON, Montoya, and in-memory adapters
│           └── ui/                        Swing suite tab
├── fixture/                               loopback replay test API
└── scripts/                               repeatable Burp and fixture workflows
```

See [Development workflow](docs/development.md),
[Community validation report](docs/community-validation-20260731.md),
[authenticated laboratory validation](docs/ui-authenticated-validation-20260730.md),
[BApp readiness matrix](docs/bapp-readiness.md),
[BApp submission text](docs/bapp-submission.md),
[0.3.3 release notes](docs/release-notes-0.3.3.md),
[release integrity process](docs/release-integrity.md),
[Invariant language](docs/invariants.md),
[Workflow files](docs/workflow-files.md), [Architecture](docs/architecture.md),
[Roadmap](docs/roadmap.md), and [Contributing](CONTRIBUTING.md) for the
controlled local test environment and design boundaries.

## Local replay fixture

The `fixture` subproject provides a loopback-only invitation lifecycle API with secure and intentionally vulnerable modes:

```powershell
.\scripts\run-fixture.ps1 -Mode vulnerable
```

Start Burp with the development extension in a second terminal:

```powershell
.\scripts\run-burp-dev.ps1 `
  -ProjectConfigFile .\lab\burp-project-options.json
```

The project configuration creates the dedicated loopback proxy listener on
`127.0.0.1:8080` with interception disabled. The complete proxy walkthrough and
the authorized OWASP lab campaigns are documented in
[docs/development.md](docs/development.md) and [lab/README.md](lab/README.md).

## Safe use

WorkflowGuard is intended only for systems you own or are explicitly authorized to test. Mutated workflows can create, update, or delete application data. Keep runs in scope, use conservative request limits, require confirmation for state-changing steps, and define verified cleanup before enabling execution.

## Design principle

Stepper executes the expected workflow. WorkflowGuard explores deliberately unexpected workflow variants and evaluates their state effects.

## License

WorkflowGuard is licensed under the
[GNU General Public License version 3](LICENSE), using the
`GPL-3.0-only` SPDX designation.
