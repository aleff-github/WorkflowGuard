[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$GuestEvidencePath,

    [Parameter(Mandatory)]
    [string]$AccountEvidencePath,

    [Parameter(Mandatory)]
    [string]$FirstActorEvidencePath,

    [Parameter(Mandatory)]
    [string]$SecondActorEvidencePath,

    [Parameter(Mandatory)]
    [string]$NativeReplayEvidencePath,

    [Parameter(Mandatory)]
    [string]$InvalidationRecordPath
)

$ErrorActionPreference = "Stop"
$repositoryRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)

function Resolve-EvidencePath {
    param([Parameter(Mandatory)][string]$Path)
    return (Resolve-Path -LiteralPath $Path).Path
}

$readmePath = Resolve-EvidencePath (Join-Path $repositoryRoot "agy\README.md")
$protocolPath = Resolve-EvidencePath (
    Join-Path $repositoryRoot "docs\dual-agent-testing.md"
)
$manifestPath = Resolve-EvidencePath (
    Join-Path $repositoryRoot "agy\engagements\active.json"
)
$guestPath = Resolve-EvidencePath $GuestEvidencePath
$accountPath = Resolve-EvidencePath $AccountEvidencePath
$firstActorPath = Resolve-EvidencePath $FirstActorEvidencePath
$secondActorPath = Resolve-EvidencePath $SecondActorEvidencePath
$nativeReplayPath = Resolve-EvidencePath $NativeReplayEvidencePath
$invalidationPath = Resolve-EvidencePath $InvalidationRecordPath

$env:WORKFLOWGUARD_AGY_WORKSPACE = $repositoryRoot
$prompt = @"
Use the workflowguard-authorized-qa skill for an evidence-review-only task.
Do not access any target, browser, URL, network tool, MCP tool, shell command,
or write tool. Read only these exact absolute files:
- $readmePath
- $protocolPath
- $manifestPath
- $guestPath
- $accountPath
- $firstActorPath
- $secondActorPath
- $nativeReplayPath
- $invalidationPath

The engagement validator and local safety self-test were independently executed
before this review and passed: activationReady=true, hookAssertions=18,
validatorAssertions=2. Do not rerun commands in this headless review.

The invalidation record is authoritative. Do not rely on any superseded
receipt or review named in it. Assess only the valid replacement evidence.

Check exact loopback scope, the configured Burp listener, sequential execution,
the 750 ms delay, approved methods and paths, request caps per case, absence of
state-changing resource requests, and absence of retained credentials,
Authorization headers, response bodies, token values, object identifiers, and
location values.

Independently determine whether:
1. The Juice Shop basket invariant is reproducibly violated in both directions
   for actor pairs A/B and C/D.
2. The native replay consistently shows production ExecutionCoordinator use,
   per-actor Authorization replacement, removal of captured Authorization,
   distinct actor tokens, actual Burp proxying, and the same bidirectional
   foreign-basket result.
3. The crAPI dashboard identity-binding invariant passes for both actor pairs.
4. The crAPI vehicle-location case is inconclusive because all four supplied
   accounts lack vehicle objects; do not reinterpret missing preconditions as
   a pass or a failure.

Do not provide exploit instructions. Return only one concise JSON object in the
final response with: reviewer, engagementId, evidenceRunIds, scopeConsistent,
safetyConsistent, nativeReplayConsistent, juiceBasketInvariant,
crapiDashboardInvariant, crapiVehicleLocationInvariant, securityFinding,
confidence, observations, limitations, disposition. Use the invariant values
VIOLATED, PASSED, INCONCLUSIVE, or NEEDS_MANUAL_REVIEW as appropriate. Do not
include credentials, headers, response bodies, object identifiers, exploit
guidance, target-specific attack instructions, or Markdown fences. Do not
create any file.
"@

& agy `
    --sandbox `
    --agent workflowguard-qa `
    --effort medium `
    --print $prompt `
    --output-format text `
    --print-timeout 3m
exit $LASTEXITCODE
