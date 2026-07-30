[CmdletBinding()]
param(
    [ValidateSet("vulnerable", "secure")]
    [string]$Mode = "vulnerable",

    [ValidateRange(1, 65535)]
    [int]$Port = 18080
)

$ErrorActionPreference = "Stop"
$repositoryRoot = Split-Path -Parent $PSScriptRoot

if (-not $env:JAVA_HOME) {
    $javaCandidates = @(
        "C:\Program Files\Android\Android Studio\jbr",
        (Join-Path $env:LOCALAPPDATA "Programs\BurpSuiteCommunity\jre")
    )
    $selectedJava = $javaCandidates |
        Where-Object { Test-Path -LiteralPath (Join-Path $_ "bin\javac.exe") } |
        Select-Object -First 1
    if (-not $selectedJava) {
        throw "JDK 21 not found. Set JAVA_HOME before running this script."
    }
    $env:JAVA_HOME = $selectedJava
}

Push-Location $repositoryRoot
try {
    Write-Host "Starting the loopback-only fixture in $Mode mode on port $Port."
    & ".\gradlew.bat" ":fixture:run" "--args=--port=$Port --mode=$Mode"
    if ($LASTEXITCODE -ne 0) {
        throw "Fixture exited with code $LASTEXITCODE."
    }
} finally {
    Pop-Location
}
