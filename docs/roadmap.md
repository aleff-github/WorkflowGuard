# Roadmap

## 0.1 — Capture and deterministic mutation

- [x] Gradle and Java 21 project.
- [x] Montoya bootstrap and suite tab.
- [x] Context-menu capture.
- [x] Immutable workflow model.
- [x] Skip, repeat, and replay mutation generation.
- [x] Regex and JSON Pointer variable resolution.
- [x] Semantic JSON diff and basic invariants.
- [x] Safety-policy evaluation.
- [x] Loopback-only secure/vulnerable replay fixture.
- [x] Developer-mode Burp and fixture launch scripts.
- [x] Scripted lifecycle traffic through the Burp proxy.
- [x] Workflow editor with CRUD, step ordering, roles, enablement, and raw-request inspection.
- [x] Project-backed workflow persistence.
- [x] Variable editor and editable request templates.
- [x] Sequential request execution through Montoya.
- [x] Per-run request limit and delay enforcement.
- [x] State-changing confirmation dialog with exact sequence preview.
- [x] Immutable run result and raw HTTP evidence models.
- [x] Multiple actor identities with editable session seeds.
- [x] Per-actor, per-origin isolated cookies and Authorization seeds.
- [x] Actor-swap mutations.
- [x] Redacted JSON evidence export.
- [x] Replay-after-revoke, replay-after-delete, and one-time-use presets.
- [x] In-session comparative result matrix.
- [x] Automatic dependency suggestions with evidence and confidence.
- [x] Interactive workflow graph.

## 0.2 — State-aware replay

- [x] multiple identities and isolated cookie jars;
- [x] actor swapping;
- [x] before/after probes;
- [x] configurable volatile JSON paths;
- [x] cleanup workflows with post-cleanup verification;
- [x] replay after revoke, delete, and one-time use presets;
- [x] result matrix;
- [x] evidence export.

## 0.3 — Workflow graph

- [x] automatic dependency suggestions;
- [x] graph representation;
- [x] stale object and token mutation;
- [x] expression-based invariants;
- [x] informational Burp audit-issue publication;
- [x] workflow import/export.

## Explicitly out of the MVP

- high-concurrency race testing;
- arbitrary fuzzing;
- generic SQL injection or XSS scanning;
- autonomous exploit generation;
- cloud services or mandatory AI;
- automatic vulnerability classification based only on response differences.
