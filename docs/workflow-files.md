# Workflow files

The **Import** and **Export** controls use a versioned `.workflowguard.json` envelope. The archive includes actors, steps, roles, request templates, variable extraction and stale-value configuration, invariants, volatile JSON paths, and creation metadata.

## Portable export

This is the default. Redaction is deliberately best effort because arbitrary
HTTP bodies and custom application formats cannot be classified perfectly.
WorkflowGuard redacts:

- authorization, proxy-authorization, cookie, set-cookie, and API-key headers;
- secret-like JSON and form fields;
- secret-like query parameters and opaque or security-sensitive path segments;
- actor cookie and authorization seeds;
- stale values whose variable name indicates a token, password, session, secret, cookie, authorization value, or API key.

Imported portable workflows can contain `<redacted>` markers. Replace those values before replay.
Actor authorization seeds are also masked in the Swing table and are never
shown as plain text after entry. Configured actor cookies and authorization
seeds are nevertheless stored in Burp project extension data, so protect the
project and review every portable export before sharing it.

## Trusted export

Select **Include session cookies, authorization headers, and sensitive stale values** only when the destination is trusted. The resulting file can contain live credentials and should be protected like a Burp project file.

## Import validation

WorkflowGuard rejects files above 10 MiB, trailing JSON documents, unsupported
archive versions, oversized collections or fields, duplicate identifiers or
variable names, request methods or Host headers that diverge from their modeled
step, unknown actor references, variables whose source step is missing, and
invariants whose source is missing or is not a `PROBE`.

If an imported workflow ID already exists in the current project, WorkflowGuard assigns a new workflow ID and appends `(imported)` to its name while preserving internal step references.
