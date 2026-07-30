# AGY authorized-testing profile

This workspace contains a Google Antigravity profile for coordinating WorkflowGuard tests without granting AGY direct target-network access.

## Installed components

- Workspace plugin: `.agents/plugins/workflowguard-qa/`
- Selectable primary agent: `workflowguard-qa`
- Skill: `workflowguard-authorized-qa`
- Always-on testing boundary: plugin rule
- PreToolUse gate: `agy-safety-hook.ps1`
- Engagement validator: `scripts/agy/Validate-WorkflowGuardEngagement.ps1`
- Configuration self-test: `scripts/agy/Test-WorkflowGuardAgyConfiguration.ps1`

The gate permits local read-only inspection, permits sanitized writes only under
`agy/output/` after explicit approval, requires approval for allowlisted local
verification commands, and denies arbitrary commands, browser tools, URL
fetching, MCP, delegation, scheduling, and permission escalation. Because the
plugin hook is workspace-wide, use this profile for QA sessions; it intentionally
blocks AGY code edits elsewhere in the repository.

## Prepare an engagement

1. Copy `agy/engagements/engagement.example.json` to `agy/engagements/active.json`.
2. Keep it `DRAFT` and `DRY_RUN` while completing scope and authorization.
3. Store only identity labels and Burp project references. Never store credentials.
4. Validate:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File scripts/agy/Validate-WorkflowGuardEngagement.ps1 `
  -Manifest agy/engagements/active.json
```

5. Run the local safety self-test:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File scripts/agy/Test-WorkflowGuardAgyConfiguration.ps1
```

`active.json` and `agy/output/` are ignored by Git.

## Start AGY

Validate and install or refresh the workspace plugin before starting AGY:

```powershell
agy plugin validate .agents/plugins/workflowguard-qa
agy plugin install .agents/plugins/workflowguard-qa
agy agents
```

The final command must list `workflowguard-qa`. Re-run `plugin install` after
changing an agent, skill, rule, hook, or hook script.

Start the CLI with its Windows terminal sandbox:

```powershell
agy --sandbox
```

Open `/agents`, select `workflowguard-qa`, and press `Esc` to apply it. Then ask:

```text
Use the workflowguard-authorized-qa skill. Validate the active engagement and prepare a dry-run test plan. Do not access any target.
```

Do not use `--dangerously-skip-permissions`.

For a non-interactive evidence review, grant `read_file` only for the installed
WorkflowGuard plugin and the exact sanitized input files. Do not use wildcard
permissions. The review launcher supplies an explicit, marker-validated
workspace boundary and does not grant target-network or command access:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File scripts/agy/Invoke-WorkflowGuardAgyEvidenceReview.ps1 `
  -EvidencePath agy/output/<engagement-id>/<sanitized-evidence>.json
```

The launcher returns the AGY review on standard output. A human or Codex reviewer
must inspect it before saving it under `agy/output/`.

## Activation boundary

The validator reports `activationReady: true` only when:

- status is `APPROVED`;
- mode is `ACTIVE`;
- approver and approval timestamp are present;
- the current time is inside the authorization window;
- origins are exact HTTP(S) origins without wildcards, paths, queries, fragments, or credentials;
- redirects remain disabled;
- request, case, delay, and concurrency limits are valid;
- state-changing methods have explicit approval and cleanup is required;
- no reserved `.invalid` placeholder remains.

Even then, AGY cannot send target traffic. It can prepare the plan and analyze redacted evidence; WorkflowGuard in Burp remains the only traffic sender and retains its own scope and confirmation gates.
