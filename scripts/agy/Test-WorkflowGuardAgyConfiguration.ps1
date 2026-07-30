[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$repositoryRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$hook = Join-Path $repositoryRoot ".agents\plugins\workflowguard-qa\scripts\agy-safety-hook.ps1"
$validator = Join-Path $PSScriptRoot "Validate-WorkflowGuardEngagement.ps1"
$example = Join-Path $repositoryRoot "agy\engagements\engagement.example.json"

function Invoke-Hook {
    param(
        [string]$ToolName,
        [hashtable]$Arguments,
        [switch]$OmitWorkspace
    )
    $payloadData = @{
        toolCall = @{
            name = $ToolName
            args = $Arguments
        }
        stepIdx = 0
    }
    if (-not $OmitWorkspace) {
        $payloadData.workspacePaths = @($repositoryRoot)
    }
    $payload = $payloadData | ConvertTo-Json -Depth 8 -Compress
    $response = $payload |
        powershell.exe -NoProfile -ExecutionPolicy Bypass -File $hook |
        ConvertFrom-Json
    if ($LASTEXITCODE -ne 0) {
        throw "Hook process failed for $ToolName."
    }
    return $response
}

function Assert-Decision {
    param(
        [string]$ToolName,
        [hashtable]$Arguments,
        [string]$Expected
    )
    $actual = Invoke-Hook -ToolName $ToolName -Arguments $Arguments
    if ($actual.decision -ne $Expected) {
        throw "Expected $Expected for $ToolName, received $($actual.decision)."
    }
}

Assert-Decision "view_file" @{ AbsolutePath = $example } "allow"
Assert-Decision "view_file" @{ AbsolutePath = (Join-Path $env:USERPROFILE ".ssh\id_rsa") } "deny"
Assert-Decision "write_to_file" @{ TargetFile = "agy/output/report.json" } "force_ask"
Assert-Decision "write_to_file" @{ TargetFile = "src/main/java/Unexpected.java" } "deny"
Assert-Decision "run_command" @{ CommandLine = ".\gradlew.bat test" } "force_ask"
Assert-Decision "run_command" @{
    CommandLine = "powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/agy/Validate-WorkflowGuardEngagement.ps1 -Manifest agy/engagements/active.json -RequireActive"
} "force_ask"
Assert-Decision "run_command" @{
    CommandLine = "powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/agy/Validate-WorkflowGuardEngagement.ps1 -Manifest C:/temp/untrusted.json"
} "deny"
Assert-Decision "run_command" @{ CommandLine = "git diff --check" } "force_ask"
Assert-Decision "run_command" @{ CommandLine = "git status > C:\temp\status.txt" } "deny"
Assert-Decision "run_command" @{ CommandLine = "curl https://example.test" } "deny"
Assert-Decision "read_url_content" @{ Url = "https://example.test" } "deny"
Assert-Decision "browser_navigate" @{ Url = "https://example.test" } "deny"
Assert-Decision "invoke_subagent" @{ Subagents = @() } "deny"
Assert-Decision "ask_permission" @{ Action = "read_url"; Target = "*" } "deny"
Assert-Decision "ask_question" @{ Question = "Confirm scope?" } "allow"
Assert-Decision "unknown_tool" @{} "deny"

$previousFallback = $env:WORKFLOWGUARD_AGY_WORKSPACE
try {
    Remove-Item Env:WORKFLOWGUARD_AGY_WORKSPACE -ErrorAction SilentlyContinue
    $withoutBoundary = Invoke-Hook `
        -ToolName "view_file" `
        -Arguments @{ AbsolutePath = $example } `
        -OmitWorkspace
    if ($withoutBoundary.decision -ne "deny") {
        throw "A missing workspace boundary must be denied."
    }

    $env:WORKFLOWGUARD_AGY_WORKSPACE = $repositoryRoot
    $withFallback = Invoke-Hook `
        -ToolName "view_file" `
        -Arguments @{ AbsolutePath = $example } `
        -OmitWorkspace
    if ($withFallback.decision -ne "allow") {
        throw "The explicit validated workspace fallback should be allowed."
    }
} finally {
    if ($null -eq $previousFallback) {
        Remove-Item Env:WORKFLOWGUARD_AGY_WORKSPACE -ErrorAction SilentlyContinue
    } else {
        $env:WORKFLOWGUARD_AGY_WORKSPACE = $previousFallback
    }
}

$validationText = & $validator -Manifest $example
if ($LASTEXITCODE -ne 0) {
    throw "The example DRY_RUN engagement should be structurally valid."
}
$validation = $validationText | ConvertFrom-Json
if (-not $validation.valid -or $validation.activationReady) {
    throw "The example must be valid for planning and not activation-ready."
}

$invalidPath = Join-Path ([IO.Path]::GetTempPath()) (
    "workflowguard-invalid-engagement-" + [Guid]::NewGuid() + ".json"
)
try {
    $invalid = Get-Content -LiteralPath $example -Raw -Encoding utf8 |
        ConvertFrom-Json
    $invalid.status = "APPROVED"
    $invalid.execution.mode = "ACTIVE"
    $invalid.scope.allowedOrigins = @("https://staging.example.invalid")
    $invalid | ConvertTo-Json -Depth 10 |
        Set-Content -LiteralPath $invalidPath -Encoding utf8

    $invalidText = & $validator -Manifest $invalidPath -RequireActive 2>$null
    if ($LASTEXITCODE -eq 0) {
        throw "Placeholder target unexpectedly became activation-ready."
    }
    $invalidValidation = $invalidText | ConvertFrom-Json
    if ($invalidValidation.activationReady) {
        throw "Invalid engagement reported activationReady=true."
    }
} finally {
    Remove-Item -LiteralPath $invalidPath -Force -ErrorAction SilentlyContinue
}

[ordered]@{
    passed = $true
    hookAssertions = 18
    validatorAssertions = 2
    mode = "DRY_RUN"
    networkEnabled = $false
} | ConvertTo-Json -Compress
