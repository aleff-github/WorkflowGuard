# Independent Codex and AGY review protocol

WorkflowGuard uses Codex and Google Antigravity (AGY) as independent reviewers.
Neither agent is a direct HTTP client for the target. WorkflowGuard inside Burp
is the only component allowed to send target traffic.

## Roles

| Component | Responsibility | Prohibited |
|---|---|---|
| Codex | Code verification, baseline model, candidate invariants, local evidence review | Direct target mutations outside WorkflowGuard |
| AGY `workflowguard-qa` | Independent workflow critique, missing-case review, redacted evidence assessment | Browser, URL fetching, arbitrary shell, direct target traffic |
| WorkflowGuard + Burp | Scoped sequential execution, actor session isolation, evidence capture | Origins outside the active manifest |
| Human operator | Scope approval, Burp identity setup, state-change confirmation, disagreement resolution | Sharing secrets with either agent |

## Two-pass process

1. Record a legitimate happy path in Burp using disposable lab identities.
2. Export only the redacted workflow structure.
3. Codex produces pass A:
   roles, resources, state transitions, invariants, cleanup, and candidate
   mutations.
4. Without seeing Codex conclusions, AGY produces pass B from the same sanitized
   workflow and active engagement manifest.
5. Compare the two passes:
   - intersection: eligible for execution;
   - unique low-risk cases: manual review before inclusion;
   - scope, identity, or cleanup disagreement: blocked.
6. WorkflowGuard executes one approved case at a time.
7. Codex and AGY independently classify the redacted evidence.
8. A finding is accepted only when both reviews agree on the violated invariant
   and the post-condition is reproducible. Otherwise it remains
   `NEEDS_MANUAL_REVIEW`.

## Required review record

Each review records:

- engagement ID and manifest SHA-256;
- workflow and mutation case IDs;
- reviewer (`codex` or `agy`);
- assumptions and expected invariant;
- observed invariant result;
- cleanup result;
- confidence;
- final disposition;
- redacted evidence paths.

Secrets and complete HTTP messages are never part of a review record.

## Transport baseline

Before recording authenticated workflows, run the loopback-only guest baseline
through Burp. Codex and AGY independently review the sanitized receipt. The
baseline is accepted only when both reviewers agree that scope and safety limits
are consistent and both target responses completed successfully.

Loopback transport must use a proxy implementation that explicitly returns
`false` from `IWebProxy.IsBypassed`. Process ownership of a listening port is
necessary but not sufficient evidence: the standard .NET proxy can bypass
`127.0.0.1` even when local bypass appears disabled. The retained receipt must
identify the configured Burp listener, and native replay evidence must record
that proxy bypass is disallowed.

An accepted baseline establishes transport readiness only. It cannot produce a
security finding and does not replace authenticated actor-isolation,
authorization, state-transition, invariant, mutation, or cleanup tests.

## Account-readiness baseline

After the transport baseline, validate each pre-provisioned disposable account
with one correct login request through Burp. Run requests sequentially with the
engagement delay and a fresh client context for each account. The retained
evidence contains only the target, account label, method and path, status code,
and a token-presence boolean. Credential values, response bodies, and token
values are never retained.

Codex checks the redacted receipt for secret leakage and internal consistency.
AGY independently reviews the same receipt without target, browser, network,
shell, or write access. Agreement establishes account readiness only; it is not
evidence of actor isolation, authorization correctness, or a security finding.

## Authenticated actor-isolation evidence

Use two independent actor pairs when four disposable identities are available.
Record only labeled checks, methods, path templates, status codes, and boolean
invariant outcomes. Never retain authorization values, object identifiers, or
response bodies in reviewer evidence.

At least one accepted authorization result must be reproduced through the
production `ExecutionCoordinator`, using per-actor session replacement and the
same Burp listener. A security finding is accepted only when Codex and AGY agree
on the invariant and the native replay reproduces it. Missing owned objects are
reported as `INCONCLUSIVE`, never as a pass or a failure.
