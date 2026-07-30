[CmdletBinding()]
param(
    [string]$BurpHome,

    [string]$DataDirectory,

    [string]$ProjectConfigFile,

    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$repositoryRoot = Split-Path -Parent $PSScriptRoot

if (-not $BurpHome) {
    if ($env:WORKFLOWGUARD_BURP_HOME) {
        $BurpHome = $env:WORKFLOWGUARD_BURP_HOME
    } else {
        $BurpHome = Join-Path $env:LOCALAPPDATA "Programs\BurpSuiteCommunity"
    }
}
if (-not $DataDirectory) {
    $DataDirectory = Join-Path $repositoryRoot "build\burp-dev-data"
}

$burpJar = Join-Path $BurpHome "burpsuite.jar"
$burpJava = Join-Path $BurpHome "jre\bin\java.exe"
if (-not (Test-Path -LiteralPath $burpJar)) {
    throw "Burp JAR not found at $burpJar. Pass -BurpHome or set WORKFLOWGUARD_BURP_HOME."
}
if (-not (Test-Path -LiteralPath $burpJava)) {
    throw "Burp Java runtime not found at $burpJava."
}

if (-not $SkipBuild) {
    if (-not $env:JAVA_HOME) {
        $javaCandidates = @(
            "C:\Program Files\Android\Android Studio\jbr",
            (Join-Path $BurpHome "jre")
        )
        $selectedJava = $javaCandidates |
            Where-Object { Test-Path -LiteralPath (Join-Path $_ "bin\javac.exe") } |
            Select-Object -First 1
        if (-not $selectedJava) {
            throw "Java 21 not found. Set JAVA_HOME before running this script."
        }
        $env:JAVA_HOME = $selectedJava
    }

    Push-Location $repositoryRoot
    try {
        & ".\gradlew.bat" "jar"
        if ($LASTEXITCODE -ne 0) {
            throw "Extension build failed with code $LASTEXITCODE."
        }
    } finally {
        Pop-Location
    }
}

$extensionJar = Get-ChildItem -LiteralPath (Join-Path $repositoryRoot "build\libs") `
        -Filter "workflowguard-*.jar" |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if (-not $extensionJar) {
    throw "WorkflowGuard JAR not found. Build the project before using -SkipBuild."
}

New-Item -ItemType Directory -Path $DataDirectory -Force | Out-Null
$classPath = "$burpJar;$($extensionJar.FullName)"
$burpArguments = @(
    "-cp",
    $classPath,
    "burp.StartBurp",
    "--data-dir=$DataDirectory",
    "--use-defaults",
    "--disable-auto-update",
    "--developer-extension-class-name=Extension"
)
if ($ProjectConfigFile) {
    $resolvedProjectConfigFile = (
        Resolve-Path -LiteralPath $ProjectConfigFile -ErrorAction Stop
    ).Path
    $burpArguments += "--config-file=$resolvedProjectConfigFile"
}

Write-Host "Loading $($extensionJar.FullName)"
Write-Host "Using isolated Burp data directory $DataDirectory"
if ($ProjectConfigFile) {
    Write-Host "Loading project configuration $resolvedProjectConfigFile"
}
& $burpJava @burpArguments

if ($LASTEXITCODE -ne 0) {
    throw "Burp exited with code $LASTEXITCODE."
}
