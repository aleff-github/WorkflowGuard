# WorkflowGuard UI validation — 2026-07-30

## Outcome

WorkflowGuard `0.3.0` was exercised end to end from its Burp Suite user
interface against the local OWASP Juice Shop laboratory at
`http://127.0.0.1:3000`.

The final UI campaign completed three realistic mutation cases:

| Case | Mutation HTTP | Run | Invariant checks | Violations | Assessment |
|---|---:|---|---:|---:|---|
| Repeat catalog lookup | `200 → 200` | `COMPLETED` | 1 passed | 0 | `VERIFIED` |
| Run as isolated authenticated shopper | `200` | `COMPLETED` | 1 passed | 0 | `VERIFIED` |
| Use stale `productName` | `200` | `COMPLETED` | 1 passed | 0 | `VERIFIED` |

All target requests used `GET`. No destructive request was authorized or sent.

## Realistic workflow

The workflow `Juice Shop - catalog consistency` contains:

- a before/after probe for `GET /rest/products/search?q=banana`;
- an action whose request template uses
  `GET /rest/products/search?q=${productName}`;
- a regex variable named `productName`, extracted from the probe response;
- a configured stale value, `definitely-missing`;
- the invariant `Catalog search returns selected product`;
- the default actor and an isolated `Authenticated shopper` actor.

The request was captured from Burp's live passive crawl table through
`Extensions → WorkflowGuard → Add to active workflow`. Import, editing,
mutation generation, execution, result inspection, and both workflow and
evidence export were performed from the WorkflowGuard UI.

## UI regression found and fixed

Editing a raw request in the Swing text area converted its line endings from
HTTP CRLF to LF. Before the fix, Montoya replayed the visible request with LF
line endings and Juice Shop returned HTTP `400`.

The fix normalizes raw HTTP/1 request text to CRLF:

- when the UI saves an edited request template; and
- defensively at the Montoya send boundary.

The regression was verified from the UI in two independent ways:

1. A portable workflow containing the legacy LF-only action template was
   imported into a fresh Burp instance and replayed without editing. The
   repeat case completed four exchanges, all with HTTP `200`.
2. The action template was edited and saved from the UI. The exported workflow
   contains seven CRLF delimiters and zero bare LF delimiters for the action
   request.

The focused tests are in `HttpRequestTextTest`. The complete automated build
finished with 65 tests, 0 failures, 0 errors, and one opt-in laboratory test
skipped by the default run. That real-lab actor-authorization test was then run
separately with its laboratory environment enabled and passed.

## Safety and secret handling

- Marking the action as `UPDATE` and pressing **Run selected case** displayed
  the state-changing confirmation with all four planned requests. The run was
  cancelled; zero state-changing requests were sent.
- Actor authorization is masked as `<configured>` in the table.
- Portable workflow export reported `secretsIncluded: false`.
- The exported workflow and redacted evidence contain no Authorization header
  and no Bearer token pattern.
- The evidence export reports `redactionApplied: true`.

## Curated screenshots

These screenshots document the historical `0.3.0` campaign. They use the
pre-`0.3.2` label **Publish invariant failures to Scanner**; the current UI uses
the more precise **Publish invariant failures to Burp issues**, with an
edition-aware Community notice. The tested execution behavior is unchanged.

1. [Laboratory traffic with HTTP 200 responses](screenshots/ui-validation-20260730/selected/01-lab-traffic.png)
2. [Capture from the Burp context menu](screenshots/ui-validation-20260730/selected/02-capture-context-menu.png)
3. [Portable workflow imported in a fresh Burp instance](screenshots/ui-validation-20260730/selected/03-imported-workflow.png)
4. [Masked actor authorization](screenshots/ui-validation-20260730/selected/04-actor-authorization-redacted.png)
5. [Workflow graph and inferred dependency](screenshots/ui-validation-20260730/selected/05-dependency-graph.png)
6. [Variable request template saved from the UI](screenshots/ui-validation-20260730/selected/06-variable-template.png)
7. [Generated repeat, actor-swap, and stale-variable cases](screenshots/ui-validation-20260730/selected/07-generated-cases.png)
8. [Final result matrix](screenshots/ui-validation-20260730/selected/08-result-matrix.png)
9. [Three HTTP 200 exchanges for the stale-variable case](screenshots/ui-validation-20260730/selected/09-http-evidence.png)
10. [Passed invariant evidence](screenshots/ui-validation-20260730/selected/10-invariant-evidence.png)
11. [Explicit state-changing confirmation](screenshots/ui-validation-20260730/selected/11-state-change-confirmation.png)
12. [Portable export secret-redaction warning](screenshots/ui-validation-20260730/selected/12-export-redaction.png)

## Retained artifacts

- [Portable post-fix workflow](evidence/ui-validation-20260730/juice-shop-catalog-ui-postfix.workflowguard.json)
- [Redacted UI evidence](evidence/ui-validation-20260730/juice-shop-ui-evidence-20260730.json)
- [Machine-readable validation summary](evidence/ui-validation-20260730/ui-validation-summary-20260730.json)
- Historical artifact name: `build/libs/workflowguard-0.3.0.jar`

This campaign validates the exercised UI paths and laboratory scenario. It is
not a claim that every possible target, operating system, Burp release, or
failure mode has been exhaustively tested.
