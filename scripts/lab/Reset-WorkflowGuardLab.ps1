[CmdletBinding()]
param(
    [switch]$ConfirmReset
)

$ErrorActionPreference = "Stop"
if (-not $ConfirmReset) {
    throw "Reset deletes disposable lab containers and volumes. Re-run with -ConfirmReset."
}

$repositoryRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$juiceCompose = Join-Path $repositoryRoot "lab\docker-compose.yml"
$crapiOverride = Join-Path $repositoryRoot "lab\crapi.override.yml"
$crapiCompose = Join-Path $repositoryRoot `
    ".workflowguard-lab\crAPI-1.1.6\deploy\docker\docker-compose.yml"

& docker compose --project-name workflowguard-juice-shop `
    -f $juiceCompose down --volumes --remove-orphans
if ($LASTEXITCODE -ne 0) {
    throw "Unable to reset Juice Shop."
}

if (Test-Path -LiteralPath $crapiCompose) {
    Push-Location (Split-Path -Parent $crapiCompose)
    try {
        & docker compose --project-name workflowguard-crapi `
            -f $crapiCompose -f $crapiOverride --compatibility `
            down --volumes --remove-orphans
        if ($LASTEXITCODE -ne 0) {
            throw "Unable to reset crAPI."
        }
    } finally {
        Pop-Location
    }
}

[ordered]@{
    reset = $true
    removedProjects = @(
        "workflowguard-juice-shop",
        "workflowguard-crapi"
    )
} | ConvertTo-Json -Compress
