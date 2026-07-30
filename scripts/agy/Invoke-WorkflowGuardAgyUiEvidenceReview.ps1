[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$SummaryPath
)

$ErrorActionPreference = "Stop"
$repositoryRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$resolvedSummaryPath = (Resolve-Path -LiteralPath $SummaryPath).Path
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
- $resolvedSummaryPath

The engagement validator and local safety self-test were independently executed
before this review and passed. Do not rerun commands in this headless review.

Review the sanitized WorkflowGuard UI campaign summary. Check that the scope is
loopback-only, the replay methods are read-only, execution is sequential, all
generated cases were executed, and no state-changing request was made.

Keep two assessment layers separate:
1. A WorkflowGuard VERIFIED assessment means the replay completed and the
   configured probe invariant passed.
2. It does not prove that the target is secure. Evaluate the independent
   actor-isolation conclusions in the summary.

Independently determine whether the Juice Shop basket evidence is a candidate
horizontal-authorization violation, whether the crAPI dashboard identity
binding passes, and whether the crAPI vehicle-location case remains
inconclusive because its resource precondition is absent.

Also assess the privacy observation. The original UI evidence redacted
Authorization values but retained personally identifying JSON response fields.
The summary records a post-fix UI replay and whether live Authorization values
or response e-mail values remain. Decide whether the privacy hardening is now
verified or still needs manual review.

Return only one concise JSON object in the final response with: reviewer,
engagementId, scopeConsistent, safetyConsistent, uiExecutionComplete,
workflowGuardAssessmentMeaning, juiceBasketInvariant,
crapiDashboardInvariant, crapiVehicleLocationInvariant, privacyFinding,
securityFinding, confidence, observations, limitations, disposition.
Use VIOLATED, PASSED, INCONCLUSIVE, or NEEDS_MANUAL_REVIEW for invariant
values. Do not include credentials, headers, response bodies, personal data,
object identifiers, exploit guidance, target-specific attack instructions, or
Markdown fences. Do not create any file.
"@

& agy `
    --sandbox `
    --agent workflowguard-qa `
    --effort medium `
    --print $prompt `
    --output-format text `
    --print-timeout 3m
exit $LASTEXITCODE
