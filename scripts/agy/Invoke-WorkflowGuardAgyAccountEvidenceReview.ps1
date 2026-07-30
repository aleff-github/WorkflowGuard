[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$EvidencePath
)

$ErrorActionPreference = "Stop"
$repositoryRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$resolvedEvidencePath = (Resolve-Path -LiteralPath $EvidencePath).Path
$manifestPath = (
    Resolve-Path -LiteralPath (
        Join-Path $repositoryRoot "agy\engagements\active.json"
    )
).Path
$readmePath = (
    Resolve-Path -LiteralPath (Join-Path $repositoryRoot "agy\README.md")
).Path
$protocolPath = (
    Resolve-Path -LiteralPath (
        Join-Path $repositoryRoot "docs\dual-agent-testing.md"
    )
).Path

$env:WORKFLOWGUARD_AGY_WORKSPACE = $repositoryRoot
$prompt = @"
Use the workflowguard-authorized-qa skill for an evidence-review-only task.
Do not access any target, browser, URL, network tool, MCP tool, shell command,
or write tool. Read only these exact absolute files:
- $readmePath
- $protocolPath
- $manifestPath
- $resolvedEvidencePath

The engagement validator and local safety self-test were independently executed
before this review and passed: activationReady=true, hookAssertions=18,
validatorAssertions=2. Do not rerun commands in this headless review.

Assess whether the sanitized account-readiness evidence is internally
consistent with the approved local engagement. Validate exact loopback scope,
the two approved login POST paths, eight requests total, four labeled accounts
per target, concurrency=1, delay=750ms, no intentionally invalid credentials,
no destructive requests, all HTTP statuses, token-presence booleans, and the
absence of credential values, response bodies, and token values.

This evidence may establish only that the pre-provisioned lab accounts can
authenticate through Burp. It cannot establish authorization isolation,
business-logic safety, or a security finding.

Return only one concise JSON object in the final response with: reviewer,
engagementId, evidenceRunId, scopeConsistent, safetyConsistent, accountReady,
securityFinding, confidence, observations, limitations, disposition.
Do not include credentials, headers, response bodies, exploit guidance,
target-specific attack instructions, or Markdown fences. Do not create any
file.
"@

& agy `
    --sandbox `
    --agent workflowguard-qa `
    --effort medium `
    --print $prompt `
    --output-format text `
    --print-timeout 3m
exit $LASTEXITCODE
