[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$repositoryRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$juiceCompose = Join-Path $repositoryRoot "lab\docker-compose.yml"
$crapiOverride = Join-Path $repositoryRoot "lab\crapi.override.yml"
$crapiCompose = Join-Path $repositoryRoot `
    ".workflowguard-lab\crAPI-1.1.6\deploy\docker\docker-compose.yml"

& docker compose --project-name workflowguard-juice-shop `
    -f $juiceCompose stop
if ($LASTEXITCODE -ne 0) {
    throw "Unable to stop Juice Shop."
}

if (Test-Path -LiteralPath $crapiCompose) {
    Push-Location (Split-Path -Parent $crapiCompose)
    try {
        & docker compose --project-name workflowguard-crapi `
            -f $crapiCompose -f $crapiOverride --compatibility stop
        if ($LASTEXITCODE -ne 0) {
            throw "Unable to stop crAPI."
        }
    } finally {
        Pop-Location
    }
}

[ordered]@{
    stopped = $true
    dataPreserved = $true
} | ConvertTo-Json -Compress
