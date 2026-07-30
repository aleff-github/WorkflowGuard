[CmdletBinding()]
param(
    [switch]$SkipPull,
    [ValidateRange(30, 300)]
    [int]$ReadyTimeoutSeconds = 180
)

$ErrorActionPreference = "Stop"
$repositoryRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$runtimeRoot = Join-Path $repositoryRoot ".workflowguard-lab"
$downloadRoot = Join-Path $runtimeRoot "downloads"
$juiceCompose = Join-Path $repositoryRoot "lab\docker-compose.yml"
$crapiOverride = Join-Path $repositoryRoot "lab\crapi.override.yml"
$crapiVersion = "1.1.6"
$crapiArchive = Join-Path $downloadRoot "crapi-v$crapiVersion.zip"
$crapiArchiveSha256 = "F92C4D582E4E8D6A7F10CA087A8AEBDB650867EF11D29FE1FC0F536AE38801EE" # gitleaks:allow -- pinned public archive checksum
$crapiRoot = Join-Path $runtimeRoot "crAPI-$crapiVersion"
$crapiCompose = Join-Path $crapiRoot "deploy\docker\docker-compose.yml"
$crapiVersionFile = Join-Path $crapiRoot "VERSION"

function Assert-DockerReady {
    & docker info --format "{{.ServerVersion}}" 2>$null | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Docker Desktop is not running. Start it, then retry."
    }
}

function Invoke-CheckedDocker {
    param([string[]]$Arguments)
    & docker @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Docker command failed: docker $($Arguments -join ' ')"
    }
}

function Wait-LocalHttp {
    param(
        [string]$Name,
        [Uri]$Uri,
        [DateTimeOffset]$Deadline
    )

    while ([DateTimeOffset]::UtcNow -lt $Deadline) {
        try {
            $response = Invoke-WebRequest -Uri $Uri -Method Get `
                -UseBasicParsing -TimeoutSec 5 `
                -Headers @{ "User-Agent" = "WorkflowGuard-Lab-Smoke/1.0" }
            if ([int]$response.StatusCode -ge 200 -and
                [int]$response.StatusCode -lt 500) {
                return
            }
        } catch {
            Start-Sleep -Seconds 2
        }
    }
    throw "$Name did not become reachable at $Uri before the timeout."
}

Assert-DockerReady
New-Item -ItemType Directory -Path $downloadRoot -Force | Out-Null

if (-not (Test-Path -LiteralPath $crapiCompose)) {
    if (-not (Test-Path -LiteralPath $crapiArchive)) {
        $archiveUri = "https://github.com/OWASP/crAPI/archive/refs/tags/v$crapiVersion.zip"
        Write-Host "Downloading official crAPI v$crapiVersion source archive..."
        Invoke-WebRequest -Uri $archiveUri -OutFile $crapiArchive `
            -UseBasicParsing
    }

    Write-Host "Expanding crAPI v$crapiVersion..."
    Expand-Archive -LiteralPath $crapiArchive -DestinationPath $runtimeRoot `
        -Force
}

$actualArchiveSha256 = (
    Get-FileHash -LiteralPath $crapiArchive -Algorithm SHA256
).Hash
if ($actualArchiveSha256 -ne $crapiArchiveSha256) {
    throw "The crAPI source archive SHA-256 does not match the pinned value."
}

if (-not (Test-Path -LiteralPath $crapiVersionFile)) {
    throw "crAPI VERSION file is missing after extraction."
}
$downloadedVersion = (
    Get-Content -LiteralPath $crapiVersionFile -Raw -Encoding utf8
).Trim().TrimStart("v")
if ($downloadedVersion -ne "1.1.5") {
    throw "The pinned v$crapiVersion tag has an unexpected embedded VERSION: $downloadedVersion."
}

if (-not $SkipPull) {
    Invoke-CheckedDocker @(
        "compose",
        "--project-name", "workflowguard-juice-shop",
        "-f", $juiceCompose,
        "pull"
    )
}
Invoke-CheckedDocker @(
    "compose",
    "--project-name", "workflowguard-juice-shop",
    "-f", $juiceCompose,
    "up", "-d"
)

$previousListenIp = $env:LISTEN_IP
try {
    $env:LISTEN_IP = "127.0.0.1"
    Push-Location (Split-Path -Parent $crapiCompose)
    try {
        if (-not $SkipPull) {
            Invoke-CheckedDocker @(
                "compose",
                "--project-name", "workflowguard-crapi",
                "-f", $crapiCompose,
                "-f", $crapiOverride,
                "--compatibility",
                "pull"
            )
        }
        Invoke-CheckedDocker @(
            "compose",
            "--project-name", "workflowguard-crapi",
            "-f", $crapiCompose,
            "-f", $crapiOverride,
            "--compatibility",
            "up", "-d"
        )
    } finally {
        Pop-Location
    }
} finally {
    if ($null -eq $previousListenIp) {
        Remove-Item Env:LISTEN_IP -ErrorAction SilentlyContinue
    } else {
        $env:LISTEN_IP = $previousListenIp
    }
}

$deadline = [DateTimeOffset]::UtcNow.AddSeconds($ReadyTimeoutSeconds)
Wait-LocalHttp -Name "OWASP Juice Shop" `
    -Uri ([Uri]"http://127.0.0.1:3000") -Deadline $deadline
Wait-LocalHttp -Name "OWASP crAPI" `
    -Uri ([Uri]"http://127.0.0.1:8888") -Deadline $deadline

[ordered]@{
    ready = $true
    loopbackOnly = $true
    targets = @(
        [ordered]@{
            name = "juice-shop"
            version = "19.2.1"
            origin = "http://127.0.0.1:3000"
        },
        [ordered]@{
            name = "crapi"
            sourceTag = "v$crapiVersion"
            embeddedVersion = $downloadedVersion
            origin = "http://127.0.0.1:8888"
        }
    )
    mailhog = "http://127.0.0.1:18025"
} | ConvertTo-Json -Depth 6
