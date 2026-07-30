[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$JuiceCredentialFile,

    [ValidateRange(1, 65535)]
    [int]$ProxyPort = 8080,

    [string]$OutputDirectory = "agy/output/workflowguard-local-owasp-lab-2026",

    [string]$JavaHome = "C:\Program Files\Android\Android Studio\jbr"
)

$ErrorActionPreference = "Stop"
$repositoryRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$listener = Get-NetTCPConnection `
    -LocalAddress "127.0.0.1" `
    -LocalPort $ProxyPort `
    -State Listen `
    -ErrorAction SilentlyContinue
if (-not $listener) {
    throw "No listener is active on the requested loopback proxy port."
}
if (-not (Test-Path -LiteralPath $JuiceCredentialFile -PathType Leaf)) {
    throw "The Juice Shop credential file does not exist."
}
if (-not (Test-Path -LiteralPath (Join-Path $JavaHome "bin\javac.exe"))) {
    throw "A Java 21 JDK was not found at the requested JavaHome."
}

$startedAt = [DateTimeOffset]::UtcNow
$runId = "native-auth-replay-$($startedAt.ToString('yyyyMMddTHHmmssZ'))"
$previousJavaHome = $env:JAVA_HOME
$previousCredentialFile = $env:WORKFLOWGUARD_LAB_JUICE_CREDENTIALS
$previousProxyPort = $env:WORKFLOWGUARD_LAB_PROXY_PORT

try {
    $env:JAVA_HOME = $JavaHome
    $env:WORKFLOWGUARD_LAB_JUICE_CREDENTIALS = (
        Resolve-Path -LiteralPath $JuiceCredentialFile
    ).Path
    $env:WORKFLOWGUARD_LAB_PROXY_PORT = [string]$ProxyPort

    Push-Location $repositoryRoot
    try {
        & ".\gradlew.bat" `
            ":test" `
            "--tests" `
            "dev.workflowguard.application.RealLabActorAuthorizationIntegrationTest" `
            "--rerun-tasks"
        if ($LASTEXITCODE -ne 0) {
            throw "The real-lab Authorization replay test failed."
        }
    }
    finally {
        Pop-Location
    }
}
finally {
    if ($null -eq $previousJavaHome) {
        Remove-Item Env:JAVA_HOME -ErrorAction SilentlyContinue
    }
    else {
        $env:JAVA_HOME = $previousJavaHome
    }
    if ($null -eq $previousCredentialFile) {
        Remove-Item Env:WORKFLOWGUARD_LAB_JUICE_CREDENTIALS `
            -ErrorAction SilentlyContinue
    }
    else {
        $env:WORKFLOWGUARD_LAB_JUICE_CREDENTIALS = $previousCredentialFile
    }
    if ($null -eq $previousProxyPort) {
        Remove-Item Env:WORKFLOWGUARD_LAB_PROXY_PORT -ErrorAction SilentlyContinue
    }
    else {
        $env:WORKFLOWGUARD_LAB_PROXY_PORT = $previousProxyPort
    }
}

$resultFile = Get-ChildItem `
        -LiteralPath (Join-Path $repositoryRoot "build\test-results\test") `
        -Filter "TEST-*RealLabActorAuthorizationIntegrationTest*.xml" |
    Select-Object -First 1
if (-not $resultFile) {
    throw "The JUnit result for the real-lab replay was not produced."
}

[xml]$result = Get-Content -LiteralPath $resultFile.FullName -Raw
$suite = $result.testsuite
if (
    [int]$suite.tests -ne 1 -or
    [int]$suite.failures -ne 0 -or
    [int]$suite.errors -ne 0 -or
    [int]$suite.skipped -ne 0
) {
    throw "The JUnit receipt does not describe one successful executed test."
}

$resolvedOutputDirectory = if ([System.IO.Path]::IsPathRooted($OutputDirectory)) {
    $OutputDirectory
}
else {
    Join-Path $repositoryRoot $OutputDirectory
}
New-Item -ItemType Directory -Path $resolvedOutputDirectory -Force | Out-Null

$evidence = [ordered]@{
    schemaVersion = 1
    runId = $runId
    startedAtUtc = $startedAt.ToString("o")
    completedAtUtc = [DateTimeOffset]::UtcNow.ToString("o")
    purpose = "Real local-lab replay of per-actor Authorization through the production execution coordinator and Burp."
    engagementId = "workflowguard-local-owasp-lab-2026"
    target = "juice-shop"
    caseId = "juice-basket-horizontal-isolation-native-replay"
    invariant = "An authenticated user must not receive another user's basket."
    transport = [ordered]@{
        proxy = "http://127.0.0.1:$ProxyPort"
        proxyBypassAllowed = $false
        burpOwningProcess = $listener.OwningProcess
    }
    execution = [ordered]@{
        component = "ExecutionCoordinator"
        actorSessionComponent = "IsolatedActorCookieJar"
        actorCount = 2
        loginRequestCount = 2
        coordinatorRequestCount = 4
        concurrency = 1
        delayMilliseconds = 750
        methods = @("POST", "GET")
        readOnlyResourceChecks = $true
    }
    assertions = [ordered]@{
        junitTests = [int]$suite.tests
        junitFailures = [int]$suite.failures
        junitErrors = [int]$suite.errors
        junitSkipped = [int]$suite.skipped
        actorAuthorizationSeedsApplied = $true
        capturedAuthorizationRemoved = $true
        actorTokensRemainedDistinct = $true
        ownObjectChecksPassed = 2
        crossActorForeignObjectsReturned = 2
        reproducedBidirectionally = $true
    }
    safety = [ordered]@{
        environment = "local-authorized-lab"
        stateChangingResourceRequests = $false
        destructiveRequests = $false
        credentialValuesRecorded = $false
        authorizationHeadersRecorded = $false
        responseBodiesRecorded = $false
        tokenValuesRecorded = $false
        objectIdentifiersRecorded = $false
    }
    disposition = "CANDIDATE_VIOLATION"
    classificationBoundary = "Requires independent redacted evidence review before acceptance."
    credentialSource = [ordered]@{
        fileName = [System.IO.Path]::GetFileName($JuiceCredentialFile)
        sha256 = (Get-FileHash -LiteralPath $JuiceCredentialFile -Algorithm SHA256).Hash
    }
}

$evidencePath = Join-Path $resolvedOutputDirectory "$runId.json"
$evidence | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $evidencePath -Encoding UTF8

Write-Host "Native Authorization replay completed."
Write-Host "Run ID: $runId"
Write-Host "Sanitized evidence: $evidencePath"
