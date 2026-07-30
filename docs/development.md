# Development workflow

## Prerequisites

- JDK 21.
- Burp Suite Community or Professional compatible with Montoya API `2026.7`.

All test traffic described here targets `127.0.0.1`. The fixture refuses to bind to non-loopback interfaces.

## 1. Start the vulnerable fixture

From the repository root:

```powershell
.\scripts\run-fixture.ps1
```

Equivalent Gradle command:

```powershell
.\gradlew.bat :fixture:run --args="--port=18080 --mode=vulnerable"
```

Secure comparison mode:

```powershell
.\scripts\run-fixture.ps1 -Mode secure
```

The fixture exposes:

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/health` | Mode and readiness |
| `POST` | `/test/reset` | Deterministic state reset |
| `POST` | `/api/invitations` | Create an invitation |
| `POST` | `/api/invitations/{id}/accept` | Accept or replay an invitation |
| `DELETE` | `/api/members/{id}` | Revoke membership |
| `GET` | `/api/organizations/org-1/members` | State probe |

In vulnerable mode, replaying an invitation after revocation returns `409` while restoring membership. This deliberately models a response/state inconsistency.

## 2. Start Burp with the development extension

In a second terminal:

```powershell
.\scripts\run-burp-dev.ps1 `
  -ProjectConfigFile .\lab\burp-project-options.json
```

The script:

- builds the extension;
- places the extension JAR on Burp's classpath;
- loads the public `Extension` class through Burp's developer option;
- uses `build/burp-dev-data` instead of the personal Burp data directory;
- disables automatic updates for the isolated session.
- optionally imports an explicit Burp project configuration. The supplied lab
  configuration creates a loopback listener on port `8080` with interception
  disabled.

If Burp is installed elsewhere:

```powershell
.\scripts\run-burp-dev.ps1 -BurpHome "C:\path\to\BurpSuiteCommunity"
```

## 3. Send the workflow through Burp

Find the active listener port under Burp proxy settings. Then run:

```powershell
.\scripts\send-fixture-workflow-through-burp.ps1 -ProxyPort 8080
```

The script sends:

```text
RESET
→ CREATE invitation
→ ACCEPT invitation
→ REVOKE member
→ PROBE members
→ REPLAY acceptance
→ PROBE members
```

Expected vulnerable result:

```text
Replay HTTP status: 409
Members before replay: 0
stateChanged: true
Members after replay: 1
```

Expected secure result:

```text
Replay HTTP status: 409
Members before replay: 0
stateChanged: false
Members after replay: 0
```

## 4. Capture in WorkflowGuard

1. Open Burp Proxy history.
2. Select the relevant fixture requests.
3. Right-click and choose **WorkflowGuard → Add to active workflow**.
4. Open the **WorkflowGuard** tab.
5. Confirm the steps and their order.
6. In **Actors**, keep the default captured-session actor or add identities with explicit initial `Cookie`, masked `Authorization`, or both. Each identity receives an isolated cookie jar when a run starts. A custom actor without an authorization seed cannot inherit a captured bearer token. Explicit credentials are bound to one origin; create a separate actor definition if a workflow legitimately spans origins.
7. Assign an actor to each step. Keep lifecycle requests as `ACTION`, assign the members request to `PROBE`, and optionally capture the member deletion a second time as `CLEANUP`.
8. Add `invitationId` in the **Variables** editor, sourced from the create response with JSON Pointer `/id`. Regex extractors use RE2/J syntax and intentionally reject backreferences and other constructs that cannot retain linear-time evaluation. To exercise stale-token handling, edit the variable and set a known expired or retired identifier under **Stale object/token value**.
9. Replace the invitation identifier in the accept request template with `${invitationId}`, then save the template.
10. Add an `UNCHANGED` invariant for `/count`, sourced from the members probe. Alternatively use `EXPRESSION` with `after:/count == before:/count`. Configure request IDs or timestamps under **Volatile JSON paths** when a root `UNCHANGED` rule should ignore them.
11. Open **Workflow graph**. Confirm the high-confidence `${invitationId}` data edge from creation to acceptance and the lifecycle edge from acceptance to revocation. Probe and cleanup steps occupy separate lanes.
12. Enable **State-aware presets** and **Stale value**, generate mutations, and select **Replay accept after revocation**, **Use stale invitationId**, or a case that swaps an action to another actor.
13. Review the expanded actor-labelled sequence, request cap, delay, and scope restriction, then select **Run selected case**.
14. Compare repeated executions under **Result matrix**. The assessment distinguishes the vulnerable state change, successful cleanup restoration, failed cleanup, execution failure, and runs without invariant checks. Only the latest run retains complete HTTP exchanges; previous rows are compacted to bounded metadata.
15. Select the latest matrix row and use **Export redacted JSON** to save conservative evidence without HTTP headers or bodies, response reason phrases, cookie names, query values, identifiers, extracted values, authorization headers, tokens, passwords, API keys, or cookie values.
16. With **Publish invariant failures to Burp issues** enabled, confirm that a failed post-mutation invariant is submitted as an informational WorkflowGuard audit issue. Burp Professional exposes it in **All issues**; Community accepts the API call but keeps that viewer behind the Pro license, so verify the submission message and inspect the same evidence in WorkflowGuard.
17. Use **Export** to save a portable redacted workflow, then **Import** it as a separate workflow. Replace any `<redacted>` session seed before replay.

In vulnerable mode, the post-mutation `/count` invariant fails and the post-cleanup comparison passes. HTTP `409` is retained as evidence: it does not by itself prove that application state remained unchanged.

## Automated verification

Run every extension and fixture test:

```powershell
.\gradlew.bat clean test
```

The fixture tests run secure and vulnerable lifecycle variants on ephemeral loopback ports. The root execution integration test additionally exercises the replay-after-revoke preset, dynamic invitation extraction, before/after invariant detection, cleanup, and post-cleanup verification against the vulnerable fixture.

The current Community Edition UI security regression and the preceding full
execution campaign—including manual JAR loading, scope blocking, execution,
cleanup, issue publication behavior, evidence export, unload/reload, and
screenshots—are recorded in
[community-validation-20260731.md](community-validation-20260731.md).
