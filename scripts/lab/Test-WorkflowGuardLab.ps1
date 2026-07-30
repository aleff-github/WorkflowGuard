[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"

function Get-LocalTargetStatus {
    param(
        [string]$Name,
        [Uri]$Uri
    )

    try {
        $response = Invoke-WebRequest -Uri $Uri -Method Get `
            -UseBasicParsing -TimeoutSec 10 `
            -Headers @{ "User-Agent" = "WorkflowGuard-Lab-Smoke/1.0" }
        return [ordered]@{
            name = $Name
            origin = $Uri.AbsoluteUri.TrimEnd("/")
            reachable = $true
            statusCode = [int]$response.StatusCode
        }
    } catch {
        return [ordered]@{
            name = $Name
            origin = $Uri.AbsoluteUri.TrimEnd("/")
            reachable = $false
            statusCode = $null
            error = $_.Exception.Message
        }
    }
}

& docker info --format "{{.ServerVersion}}" 2>$null | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "Docker Desktop is not running."
}

$targets = @()
$targets += Get-LocalTargetStatus -Name "juice-shop" `
    -Uri ([Uri]"http://127.0.0.1:3000")
$targets += Get-LocalTargetStatus -Name "crapi" `
    -Uri ([Uri]"http://127.0.0.1:8888")
$targets += Get-LocalTargetStatus -Name "crapi-mailhog" `
    -Uri ([Uri]"http://127.0.0.1:18025")
$passed = ($targets | Where-Object { -not $_.reachable }).Count -eq 0

[ordered]@{
    passed = $passed
    networkBoundary = "loopback-only"
    stateChanged = $false
    targets = $targets
} | ConvertTo-Json -Depth 6

if (-not $passed) {
    exit 2
}
