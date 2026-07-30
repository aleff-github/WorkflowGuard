# Security policy

## Reporting a vulnerability

Do not open a public issue for a vulnerability that could put WorkflowGuard
users or their target data at risk. Use
[GitHub private vulnerability reporting](https://github.com/aleff-github/WorkflowGuard/security/advisories/new).
If that channel is temporarily unavailable, open a minimal issue requesting a
private contact channel without including vulnerability details.

Include:

- the affected version or commit;
- reproduction steps;
- expected and observed behavior;
- the potential impact;
- a suggested mitigation, if known.

## Operational expectations

WorkflowGuard generates abnormal HTTP sequences and may replay state-changing actions. Contributors must preserve these defaults:

- no automatic execution on extension load;
- explicit request and mutation limits;
- in-scope enforcement;
- confirmation before state-changing runs;
- sequential execution for the MVP;
- no cloud dependency or telemetry;
- cleanup treated as a verified workflow, not assumed from a successful status code.
- portable workflow exports redacted by default, with an explicit warning before secrets are included;
- Burp issue publication limited to explicit invariant failures and informational severity;
- invariant expressions parsed by the restricted WorkflowGuard grammar, never a general script engine.

The intentionally vulnerable development fixture must remain bound to a loopback interface. It rejects wildcard and non-loopback addresses by design.
