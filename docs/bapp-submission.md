# WorkflowGuard BApp submission

## Name

WorkflowGuard

## One-line summary

Capture legitimate multi-step HTTP workflows, generate controlled sequence and
actor mutations, and verify their state impact with probes, invariants, and
cleanup.

## Current extension-portal form

- **Extension URL:** `https://github.com/aleff-github/WorkflowGuard`
- **Version number:** `0.3.3`
- **Additional compatible products and features:** select **Community** only.
  Professional is included automatically; WorkflowGuard does not claim DAST or
  Burp AI integration.
- **Author display name:** `Aleff`
- **Contact details:** leave blank unless a public contact address is desired.
- **Discord username:** leave blank unless the BApp Author role is desired.

The submitter must personally accept the two portal confirmations covering
permission to publish under the PortSwigger EULA and acknowledgement of the
BApp Store submission requirements.

## Description

WorkflowGuard is a state-aware workflow mutation extension for Burp Suite. It
captures related requests from Burp, models actors and workflow roles, and
generates deterministic skip, repeat, replay, actor-swap, and stale-value cases.
Each run is expanded and reviewed before execution, sent sequentially through
the Montoya API, and evaluated with before/after probes and configurable JSON
invariants. Optional cleanup steps are followed by independent post-cleanup
probes so restoration is verified rather than inferred from an HTTP status.

The extension retains an in-session result matrix and raw execution evidence,
supports redacted JSON evidence export, and can submit explicit invariant
failures as informational Burp audit issues. It works in Burp Suite Community;
Community users review issues in WorkflowGuard because Burp's **All issues**
viewer and persistent project files are Professional-only.

## How it differs from existing BApps

- Sequence Comparer compares two recorded sequences; WorkflowGuard generates
  controlled workflow mutations and executes them against explicit state
  invariants.
- AuthMatrix compares requests across users and roles; WorkflowGuard maintains
  isolated per-origin actor sessions across complete multi-step flows.
- Autorize and Auth Analyzer automatically replay requests with alternate
  credentials and classify authorization outcomes; WorkflowGuard instead
  mutates complete captured sequences and evaluates explicit before/after state
  invariants, lifecycle ordering, stale values, and verified cleanup.
- API Workflow Manager organizes endpoints; WorkflowGuard models action, probe,
  and cleanup roles, resolves dynamic variables, and verifies resulting state.

## Installation

1. Build with `./gradlew clean test jar`, or install the published BApp.
2. For a manual build, load `build/libs/workflowguard-0.3.3.jar` as a Java
   extension under **Extensions → Installed**.
3. Open the **WorkflowGuard** suite tab.

## Basic use

1. Select related requests in Proxy history or a message editor.
2. Choose **WorkflowGuard → Add to active workflow**.
3. Assign actors and `ACTION`, `PROBE`, or `CLEANUP` roles.
4. Configure dynamic variables and state invariants.
5. Generate mutations and review the complete request plan.
6. Run one approved case at a time.
7. Compare the result matrix and export redacted evidence when needed.

State-changing cases require explicit confirmation. Users should test only
systems they own or are authorized to assess.

## Compatibility and packaging

- Java 21
- Montoya API `2026.7`
- Validated with Burp Suite Community Edition `2026.7.1`
- Dependencies bundled in the release JAR
- GPL-3.0-only
- Offline operation; no telemetry or cloud dependency

## Source and validation

- Repository: <https://github.com/aleff-github/WorkflowGuard>
- [BApp readiness matrix](bapp-readiness.md)
- [Community validation](community-validation-20260731.md)
- [Authenticated laboratory validation](ui-authenticated-validation-20260730.md)
- [Development and test workflow](development.md)
- [BApp Store acceptance criteria](https://portswigger.net/burp/documentation/desktop/extend-burp/extensions/creating/bapp-store-acceptance-criteria)
