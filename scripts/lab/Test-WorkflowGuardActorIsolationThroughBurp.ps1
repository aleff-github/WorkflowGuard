[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$JuiceCredentialFile,

    [Parameter(Mandatory = $true)]
    [string]$CrapiCredentialFile,

    [string]$ProxyUrl = "http://127.0.0.1:8080",

    [string]$OutputDirectory = "agy/output/workflowguard-local-owasp-lab-2026",

    [ValidateRange(750, 60000)]
    [int]$DelayMilliseconds = 750,

    [ValidateCount(2, 2)]
    [ValidateSet("A", "B", "C", "D")]
    [string[]]$AccountLabels = @("A", "B")
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Net.Http
if ($null -eq ("WorkflowGuardForcedProxy" -as [type])) {
    Add-Type -TypeDefinition @"
using System;
using System.Net;

public sealed class WorkflowGuardForcedProxy : IWebProxy
{
    private readonly Uri proxyUri;

    public WorkflowGuardForcedProxy(Uri proxyUri)
    {
        if (proxyUri == null)
        {
            throw new ArgumentNullException("proxyUri");
        }
        this.proxyUri = proxyUri;
    }

    public ICredentials Credentials { get; set; }

    public Uri GetProxy(Uri destination)
    {
        return proxyUri;
    }

    public bool IsBypassed(Uri host)
    {
        return false;
    }
}
"@
}

function Read-LabAccounts {
    param(
        [Parameter(Mandatory = $true)]
        [string]$LiteralPath,

        [Parameter(Mandatory = $true)]
        [string]$Target,

        [Parameter(Mandatory = $true)]
        [string[]]$Labels
    )

    if (-not (Test-Path -LiteralPath $LiteralPath -PathType Leaf)) {
        throw "Credential file not found for target '$Target'."
    }

    $parsed = [ordered]@{}
    $currentLabel = $null
    foreach ($line in Get-Content -LiteralPath $LiteralPath) {
        $trimmed = $line.Trim()
        if ([string]::IsNullOrWhiteSpace($trimmed)) {
            continue
        }

        if ($trimmed -match "^Account\s+([A-D])\s*:") {
            $currentLabel = $Matches[1].ToUpperInvariant()
            if ($parsed.Contains($currentLabel)) {
                throw "Duplicate account label '$currentLabel' for target '$Target'."
            }
            $parsed[$currentLabel] = [ordered]@{}
            continue
        }

        if ($null -eq $currentLabel) {
            throw "Credential data appears before an account label for target '$Target'."
        }

        if ($trimmed -notmatch "^-\s*([^:]+?)\s*:\s*(.*)$") {
            throw "Unrecognized credential-file structure for target '$Target', account '$currentLabel'."
        }

        $rawKey = $Matches[1].Trim().ToLowerInvariant() -replace "[^a-z0-9]", ""
        $value = $Matches[2]
        $normalizedKey = switch -Regex ($rawKey) {
            "^email$" { "email"; break }
            "^password$" { "password"; break }
            "^(anwser|answer)question$" { "securityAnswer"; break }
            "^phonenumber$" { "phoneNumber"; break }
            default { $null }
        }

        if ($null -eq $normalizedKey) {
            throw "Unsupported credential field for target '$Target', account '$currentLabel'."
        }
        if ([string]::IsNullOrWhiteSpace($value)) {
            throw "Credential field '$normalizedKey' is empty for target '$Target', account '$currentLabel'."
        }

        $parsed[$currentLabel][$normalizedKey] = $value
    }

    $accounts = [ordered]@{}
    foreach ($label in $Labels) {
        if (-not $parsed.Contains($label)) {
            throw "Missing account label '$label' for target '$Target'."
        }
        foreach ($field in @("email", "password")) {
            if (-not $parsed[$label].Contains($field)) {
                throw "Missing credential field '$field' for target '$Target', account '$label'."
            }
        }

        $accounts[$label] = [pscustomobject]@{
            Label = $label
            Email = [string]$parsed[$label]["email"]
            Password = [string]$parsed[$label]["password"]
        }
    }

    if ($accounts[$Labels[0]].Email.Equals(
            $accounts[$Labels[1]].Email,
            [System.StringComparison]::OrdinalIgnoreCase
        )) {
        throw "Selected accounts are not distinct for target '$Target'."
    }

    return $accounts
}

function Test-ProxyListener {
    param([Parameter(Mandatory = $true)][uri]$Uri)

    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $connect = $client.ConnectAsync($Uri.Host, $Uri.Port)
        if (-not $connect.Wait(3000) -or -not $client.Connected) {
            throw "The Burp proxy listener is not reachable."
        }
    }
    finally {
        $client.Dispose()
    }
}

function Invoke-ProxiedRequest {
    param(
        [Parameter(Mandatory = $true)]
        [string]$RunId,

        [Parameter(Mandatory = $true)]
        [string]$ActorLabel,

        [Parameter(Mandatory = $true)]
        [System.Net.Http.HttpMethod]$Method,

        [Parameter(Mandatory = $true)]
        [uri]$Uri,

        [Parameter(Mandatory = $true)]
        [uri]$Proxy,

        [string]$BearerToken,

        [string]$JsonBody
    )

    $handler = [System.Net.Http.HttpClientHandler]::new()
    $handler.UseProxy = $true
    $handler.Proxy = [WorkflowGuardForcedProxy]::new($Proxy)
    $handler.UseCookies = $false
    $handler.AllowAutoRedirect = $false

    $client = [System.Net.Http.HttpClient]::new($handler)
    $client.Timeout = [TimeSpan]::FromSeconds(30)
    $request = $null
    $response = $null

    try {
        $request = [System.Net.Http.HttpRequestMessage]::new($Method, $Uri)
        $request.Headers.Add("X-WorkflowGuard-Test-Run", $RunId)
        $request.Headers.Add("X-WorkflowGuard-Actor", $ActorLabel)
        $request.Headers.Accept.ParseAdd("application/json")
        if (-not [string]::IsNullOrWhiteSpace($BearerToken)) {
            $request.Headers.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new(
                "Bearer",
                $BearerToken
            )
        }
        if (-not [string]::IsNullOrEmpty($JsonBody)) {
            $request.Content = [System.Net.Http.StringContent]::new(
                $JsonBody,
                [System.Text.Encoding]::UTF8,
                "application/json"
            )
        }

        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        return [pscustomobject]@{
            StatusCode = [int]$response.StatusCode
            Body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            ErrorType = $null
        }
    }
    catch {
        $baseException = $_.Exception
        while ($null -ne $baseException.InnerException) {
            $baseException = $baseException.InnerException
        }
        return [pscustomobject]@{
            StatusCode = $null
            Body = $null
            ErrorType = $baseException.GetType().FullName
        }
    }
    finally {
        if ($null -ne $response) {
            $response.Dispose()
        }
        if ($null -ne $request) {
            $request.Dispose()
        }
        $client.Dispose()
        $handler.Dispose()
        Start-Sleep -Milliseconds $DelayMilliseconds
    }
}

function ConvertFrom-JsonSafely {
    param([AllowNull()][string]$Body)

    if ([string]::IsNullOrWhiteSpace($Body)) {
        return $null
    }
    try {
        return $Body | ConvertFrom-Json
    }
    catch {
        return $null
    }
}

function New-LoginPayload {
    param([Parameter(Mandatory = $true)][pscustomobject]$Account)

    return @{
        email = $Account.Email
        password = $Account.Password
    } | ConvertTo-Json -Compress
}

function New-SafeRequestResult {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Check,

        [Parameter(Mandatory = $true)]
        [string]$Actor,

        [Parameter(Mandatory = $true)]
        [string]$Method,

        [Parameter(Mandatory = $true)]
        [string]$PathTemplate,

        [AllowNull()]
        [Nullable[int]]$StatusCode,

        [Parameter(Mandatory = $true)]
        [bool]$ExpectedObjectReturned,

        [Parameter(Mandatory = $true)]
        [bool]$ForeignObjectReturned,

        [AllowNull()]
        [string]$ErrorType
    )

    return [pscustomobject]@{
        check = $Check
        actor = $Actor
        method = $Method
        pathTemplate = $PathTemplate
        statusCode = $StatusCode
        expectedObjectReturned = $ExpectedObjectReturned
        foreignObjectReturned = $ForeignObjectReturned
        errorType = $ErrorType
    }
}

function Get-Disposition {
    param(
        [Parameter(Mandatory = $true)]
        [bool]$PreconditionsMet,

        [Parameter(Mandatory = $true)]
        [AllowEmptyCollection()]
        [object[]]$CrossResults
    )

    if (-not $PreconditionsMet) {
        return "INCONCLUSIVE"
    }
    if (@($CrossResults | Where-Object { $_.foreignObjectReturned }).Count -gt 0) {
        return "CANDIDATE_VIOLATION"
    }

    $denied = @($CrossResults | Where-Object {
            $_.statusCode -in @(401, 403, 404) -and -not $_.foreignObjectReturned
        }).Count
    if ($denied -eq $CrossResults.Count) {
        return "PASS"
    }
    return "INCONCLUSIVE"
}

$proxyUri = [uri]$ProxyUrl
Test-ProxyListener -Uri $proxyUri
$labels = @($AccountLabels | ForEach-Object { $_.ToUpperInvariant() })
if ($labels[0] -eq $labels[1]) {
    throw "AccountLabels must contain two distinct labels."
}
$labelOne = $labels[0]
$labelTwo = $labels[1]
$juiceAccounts = Read-LabAccounts `
    -LiteralPath $JuiceCredentialFile `
    -Target "juice-shop" `
    -Labels $labels
$crapiAccounts = Read-LabAccounts `
    -LiteralPath $CrapiCredentialFile `
    -Target "crapi" `
    -Labels $labels
$runId = "actor-isolation-$([DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ'))"

$caseResults = @()

# Case 1: Juice Shop basket ownership, accounts A and B.
$juiceSessions = [ordered]@{}
$juiceRequests = @()
foreach ($label in $labels) {
    $loginResponse = Invoke-ProxiedRequest `
        -RunId $runId `
        -ActorLabel "juice-user-$($label.ToLowerInvariant())" `
        -Method ([System.Net.Http.HttpMethod]::Post) `
        -Uri ([uri]"http://127.0.0.1:3000/rest/user/login") `
        -Proxy $proxyUri `
        -JsonBody (New-LoginPayload -Account $juiceAccounts[$label])
    $loginJson = ConvertFrom-JsonSafely -Body $loginResponse.Body
    $token = if ($null -ne $loginJson) {
        [string]$loginJson.authentication.token
    }
    else {
        $null
    }
    $basketId = if ($null -ne $loginJson) {
        [string]$loginJson.authentication.bid
    }
    else {
        $null
    }

    $juiceSessions[$label] = [pscustomobject]@{
        Token = $token
        BasketId = $basketId
        LoginReady = (
            $loginResponse.StatusCode -eq 200 -and
            -not [string]::IsNullOrWhiteSpace($token) -and
            -not [string]::IsNullOrWhiteSpace($basketId)
        )
    }
    $juiceRequests += New-SafeRequestResult `
        -Check "login-$label" `
        -Actor $label `
        -Method "POST" `
        -PathTemplate "/rest/user/login" `
        -StatusCode $loginResponse.StatusCode `
        -ExpectedObjectReturned $juiceSessions[$label].LoginReady `
        -ForeignObjectReturned $false `
        -ErrorType $loginResponse.ErrorType
}

$juiceObjectsDistinct = (
    $juiceSessions[$labelOne].LoginReady -and
    $juiceSessions[$labelTwo].LoginReady -and
    $juiceSessions[$labelOne].BasketId -ne $juiceSessions[$labelTwo].BasketId
)

$juiceOwnValid = $true
foreach ($label in $labels) {
    $session = $juiceSessions[$label]
    if (-not $session.LoginReady) {
        $juiceOwnValid = $false
        continue
    }

    $ownUri = [uri](
        "http://127.0.0.1:3000/rest/basket/" +
        [uri]::EscapeDataString($session.BasketId)
    )
    $ownResponse = Invoke-ProxiedRequest `
        -RunId $runId `
        -ActorLabel "juice-user-$($label.ToLowerInvariant())" `
        -Method ([System.Net.Http.HttpMethod]::Get) `
        -Uri $ownUri `
        -Proxy $proxyUri `
        -BearerToken $session.Token
    $ownJson = ConvertFrom-JsonSafely -Body $ownResponse.Body
    $returnedId = if ($null -ne $ownJson) { [string]$ownJson.data.id } else { $null }
    $ownMatched = (
        $ownResponse.StatusCode -eq 200 -and
        $returnedId -eq $session.BasketId
    )
    if (-not $ownMatched) {
        $juiceOwnValid = $false
    }

    $juiceRequests += New-SafeRequestResult `
        -Check "own-basket-$label" `
        -Actor $label `
        -Method "GET" `
        -PathTemplate "/rest/basket/{ownBasketId}" `
        -StatusCode $ownResponse.StatusCode `
        -ExpectedObjectReturned $ownMatched `
        -ForeignObjectReturned $false `
        -ErrorType $ownResponse.ErrorType
}

$juiceCrossResults = @()
foreach ($pair in @(
        [pscustomobject]@{ Actor = $labelOne; Foreign = $labelTwo },
        [pscustomobject]@{ Actor = $labelTwo; Foreign = $labelOne }
    )) {
    $actorSession = $juiceSessions[$pair.Actor]
    $foreignSession = $juiceSessions[$pair.Foreign]
    if (-not $actorSession.LoginReady -or -not $foreignSession.LoginReady) {
        continue
    }

    $crossUri = [uri](
        "http://127.0.0.1:3000/rest/basket/" +
        [uri]::EscapeDataString($foreignSession.BasketId)
    )
    $crossResponse = Invoke-ProxiedRequest `
        -RunId $runId `
        -ActorLabel "juice-user-$($pair.Actor.ToLowerInvariant())" `
        -Method ([System.Net.Http.HttpMethod]::Get) `
        -Uri $crossUri `
        -Proxy $proxyUri `
        -BearerToken $actorSession.Token
    $crossJson = ConvertFrom-JsonSafely -Body $crossResponse.Body
    $returnedId = if ($null -ne $crossJson) { [string]$crossJson.data.id } else { $null }
    $foreignReturned = (
        $crossResponse.StatusCode -eq 200 -and
        $returnedId -eq $foreignSession.BasketId
    )
    $safeResult = New-SafeRequestResult `
        -Check "cross-basket-$($pair.Actor)-to-$($pair.Foreign)" `
        -Actor $pair.Actor `
        -Method "GET" `
        -PathTemplate "/rest/basket/{foreignBasketId}" `
        -StatusCode $crossResponse.StatusCode `
        -ExpectedObjectReturned $false `
        -ForeignObjectReturned $foreignReturned `
        -ErrorType $crossResponse.ErrorType
    $juiceCrossResults += $safeResult
    $juiceRequests += $safeResult
}

$juicePreconditions = (
    $juiceObjectsDistinct -and
    $juiceOwnValid -and
    $juiceCrossResults.Count -eq 2
)
$juiceDisposition = Get-Disposition `
    -PreconditionsMet $juicePreconditions `
    -CrossResults $juiceCrossResults
$caseResults += [pscustomobject]@{
    caseId = "juice-basket-horizontal-isolation"
    target = "juice-shop"
    invariant = "An authenticated user must not receive another user's basket."
    readOnly = $true
    objectReferencesDistinct = [bool]$juiceObjectsDistinct
    preconditionsMet = [bool]$juicePreconditions
    disposition = $juiceDisposition
    requests = $juiceRequests
}
Write-Host "Juice Shop basket isolation: $juiceDisposition"

# Cases 2 and 3: crAPI session identity and vehicle-location ownership.
$crapiSessions = [ordered]@{}
$crapiSessionRequests = @()
$crapiVehicleRequests = @()
foreach ($label in $labels) {
    $loginResponse = Invoke-ProxiedRequest `
        -RunId $runId `
        -ActorLabel "crapi-user-$($label.ToLowerInvariant())" `
        -Method ([System.Net.Http.HttpMethod]::Post) `
        -Uri ([uri]"http://127.0.0.1:8888/identity/api/auth/login") `
        -Proxy $proxyUri `
        -JsonBody (New-LoginPayload -Account $crapiAccounts[$label])
    $loginJson = ConvertFrom-JsonSafely -Body $loginResponse.Body
    $token = if ($null -ne $loginJson) { [string]$loginJson.token } else { $null }
    $loginReady = (
        $loginResponse.StatusCode -eq 200 -and
        -not [string]::IsNullOrWhiteSpace($token)
    )
    $crapiSessions[$label] = [pscustomobject]@{
        Token = $token
        DashboardId = $null
        DashboardReady = $false
        VehicleUuid = $null
        LoginReady = $loginReady
    }
    $crapiSessionRequests += New-SafeRequestResult `
        -Check "login-$label" `
        -Actor $label `
        -Method "POST" `
        -PathTemplate "/identity/api/auth/login" `
        -StatusCode $loginResponse.StatusCode `
        -ExpectedObjectReturned $loginReady `
        -ForeignObjectReturned $false `
        -ErrorType $loginResponse.ErrorType

    if (-not $loginReady) {
        continue
    }

    $dashboardResponse = Invoke-ProxiedRequest `
        -RunId $runId `
        -ActorLabel "crapi-user-$($label.ToLowerInvariant())" `
        -Method ([System.Net.Http.HttpMethod]::Get) `
        -Uri ([uri]"http://127.0.0.1:8888/identity/api/v2/user/dashboard") `
        -Proxy $proxyUri `
        -BearerToken $token
    $dashboardJson = ConvertFrom-JsonSafely -Body $dashboardResponse.Body
    $dashboardEmail = if ($null -ne $dashboardJson) {
        [string]$dashboardJson.email
    }
    else {
        $null
    }
    $dashboardId = if ($null -ne $dashboardJson) {
        [string]$dashboardJson.id
    }
    else {
        $null
    }
    $dashboardReady = (
        $dashboardResponse.StatusCode -eq 200 -and
        -not [string]::IsNullOrWhiteSpace($dashboardEmail) -and
        $dashboardEmail.Equals(
            $crapiAccounts[$label].Email,
            [System.StringComparison]::OrdinalIgnoreCase
        ) -and
        -not [string]::IsNullOrWhiteSpace($dashboardId)
    )
    $crapiSessions[$label].DashboardId = $dashboardId
    $crapiSessions[$label].DashboardReady = $dashboardReady
    $crapiSessionRequests += New-SafeRequestResult `
        -Check "own-dashboard-$label" `
        -Actor $label `
        -Method "GET" `
        -PathTemplate "/identity/api/v2/user/dashboard" `
        -StatusCode $dashboardResponse.StatusCode `
        -ExpectedObjectReturned $dashboardReady `
        -ForeignObjectReturned $false `
        -ErrorType $dashboardResponse.ErrorType

    $vehiclesResponse = Invoke-ProxiedRequest `
        -RunId $runId `
        -ActorLabel "crapi-user-$($label.ToLowerInvariant())" `
        -Method ([System.Net.Http.HttpMethod]::Get) `
        -Uri ([uri]"http://127.0.0.1:8888/identity/api/v2/vehicle/vehicles") `
        -Proxy $proxyUri `
        -BearerToken $token
    $vehiclesJson = @(ConvertFrom-JsonSafely -Body $vehiclesResponse.Body)
    $vehicleUuid = if ($vehiclesJson.Count -gt 0) {
        [string]$vehiclesJson[0].uuid
    }
    else {
        $null
    }
    $vehicleReady = (
        $vehiclesResponse.StatusCode -eq 200 -and
        -not [string]::IsNullOrWhiteSpace($vehicleUuid)
    )
    $crapiSessions[$label].VehicleUuid = $vehicleUuid
    $crapiVehicleRequests += New-SafeRequestResult `
        -Check "own-vehicle-list-$label" `
        -Actor $label `
        -Method "GET" `
        -PathTemplate "/identity/api/v2/vehicle/vehicles" `
        -StatusCode $vehiclesResponse.StatusCode `
        -ExpectedObjectReturned $vehicleReady `
        -ForeignObjectReturned $false `
        -ErrorType $vehiclesResponse.ErrorType
}

$crapiDashboardsDistinct = (
    $crapiSessions[$labelOne].DashboardReady -and
    $crapiSessions[$labelTwo].DashboardReady -and
    $crapiSessions[$labelOne].DashboardId -ne $crapiSessions[$labelTwo].DashboardId
)
$crapiSessionDisposition = if ($crapiDashboardsDistinct) { "PASS" } else { "INCONCLUSIVE" }
$caseResults += [pscustomobject]@{
    caseId = "crapi-session-dashboard-identity-binding"
    target = "crapi"
    invariant = "Each authenticated token must resolve only to its own account dashboard."
    readOnly = $true
    objectReferencesDistinct = [bool]$crapiDashboardsDistinct
    preconditionsMet = [bool]$crapiDashboardsDistinct
    disposition = $crapiSessionDisposition
    requests = $crapiSessionRequests
}
Write-Host "crAPI dashboard identity binding: $crapiSessionDisposition"

$crapiObjectsDistinct = (
    $crapiSessions[$labelOne].LoginReady -and
    $crapiSessions[$labelTwo].LoginReady -and
    -not [string]::IsNullOrWhiteSpace($crapiSessions[$labelOne].VehicleUuid) -and
    -not [string]::IsNullOrWhiteSpace($crapiSessions[$labelTwo].VehicleUuid) -and
    $crapiSessions[$labelOne].VehicleUuid -ne $crapiSessions[$labelTwo].VehicleUuid
)

$crapiOwnValid = $true
foreach ($label in $labels) {
    $session = $crapiSessions[$label]
    if (-not $session.LoginReady -or [string]::IsNullOrWhiteSpace($session.VehicleUuid)) {
        $crapiOwnValid = $false
        continue
    }

    $ownLocationUri = [uri](
        "http://127.0.0.1:8888/identity/api/v2/vehicle/" +
        [uri]::EscapeDataString($session.VehicleUuid) +
        "/location"
    )
    $ownResponse = Invoke-ProxiedRequest `
        -RunId $runId `
        -ActorLabel "crapi-user-$($label.ToLowerInvariant())" `
        -Method ([System.Net.Http.HttpMethod]::Get) `
        -Uri $ownLocationUri `
        -Proxy $proxyUri `
        -BearerToken $session.Token
    $ownJson = ConvertFrom-JsonSafely -Body $ownResponse.Body
    $returnedCarId = if ($null -ne $ownJson) { [string]$ownJson.carId } else { $null }
    $ownMatched = (
        $ownResponse.StatusCode -eq 200 -and
        $returnedCarId -eq $session.VehicleUuid
    )
    if (-not $ownMatched) {
        $crapiOwnValid = $false
    }
    $crapiVehicleRequests += New-SafeRequestResult `
        -Check "own-vehicle-location-$label" `
        -Actor $label `
        -Method "GET" `
        -PathTemplate "/identity/api/v2/vehicle/{ownVehicleUuid}/location" `
        -StatusCode $ownResponse.StatusCode `
        -ExpectedObjectReturned $ownMatched `
        -ForeignObjectReturned $false `
        -ErrorType $ownResponse.ErrorType
}

$crapiCrossResults = @()
foreach ($pair in @(
        [pscustomobject]@{ Actor = $labelOne; Foreign = $labelTwo },
        [pscustomobject]@{ Actor = $labelTwo; Foreign = $labelOne }
    )) {
    $actorSession = $crapiSessions[$pair.Actor]
    $foreignSession = $crapiSessions[$pair.Foreign]
    if (
        -not $actorSession.LoginReady -or
        [string]::IsNullOrWhiteSpace($foreignSession.VehicleUuid)
    ) {
        continue
    }

    $crossLocationUri = [uri](
        "http://127.0.0.1:8888/identity/api/v2/vehicle/" +
        [uri]::EscapeDataString($foreignSession.VehicleUuid) +
        "/location"
    )
    $crossResponse = Invoke-ProxiedRequest `
        -RunId $runId `
        -ActorLabel "crapi-user-$($pair.Actor.ToLowerInvariant())" `
        -Method ([System.Net.Http.HttpMethod]::Get) `
        -Uri $crossLocationUri `
        -Proxy $proxyUri `
        -BearerToken $actorSession.Token
    $crossJson = ConvertFrom-JsonSafely -Body $crossResponse.Body
    $returnedCarId = if ($null -ne $crossJson) { [string]$crossJson.carId } else { $null }
    $foreignReturned = (
        $crossResponse.StatusCode -eq 200 -and
        $returnedCarId -eq $foreignSession.VehicleUuid
    )
    $safeResult = New-SafeRequestResult `
        -Check "cross-vehicle-location-$($pair.Actor)-to-$($pair.Foreign)" `
        -Actor $pair.Actor `
        -Method "GET" `
        -PathTemplate "/identity/api/v2/vehicle/{foreignVehicleUuid}/location" `
        -StatusCode $crossResponse.StatusCode `
        -ExpectedObjectReturned $false `
        -ForeignObjectReturned $foreignReturned `
        -ErrorType $crossResponse.ErrorType
    $crapiCrossResults += $safeResult
    $crapiVehicleRequests += $safeResult
}

$crapiPreconditions = (
    $crapiObjectsDistinct -and
    $crapiOwnValid -and
    $crapiCrossResults.Count -eq 2
)
$crapiDisposition = Get-Disposition `
    -PreconditionsMet $crapiPreconditions `
    -CrossResults $crapiCrossResults
$caseResults += [pscustomobject]@{
    caseId = "crapi-vehicle-location-horizontal-isolation"
    target = "crapi"
    invariant = "An authenticated user must not receive another user's vehicle location."
    readOnly = $true
    objectReferencesDistinct = [bool]$crapiObjectsDistinct
    preconditionsMet = [bool]$crapiPreconditions
    disposition = $crapiDisposition
    requests = $crapiVehicleRequests
}
Write-Host "crAPI vehicle-location isolation: $crapiDisposition"

$resolvedOutputDirectory = if ([System.IO.Path]::IsPathRooted($OutputDirectory)) {
    $OutputDirectory
}
else {
    Join-Path (Get-Location) $OutputDirectory
}
New-Item -ItemType Directory -Path $resolvedOutputDirectory -Force | Out-Null

$allRequests = @($caseResults | ForEach-Object { $_.requests })
$evidence = [ordered]@{
    schemaVersion = 1
    runId = $runId
    generatedAtUtc = [DateTime]::UtcNow.ToString("o")
    purpose = "Read-only authenticated horizontal actor-isolation preflight through Burp."
    classificationBoundary = "Candidate results require independent Codex and AGY review and WorkflowGuard replay before acceptance."
    safety = [ordered]@{
        environment = "local-authorized-lab"
        proxy = $ProxyUrl
        sequential = $true
        concurrency = 1
        delayMilliseconds = $DelayMilliseconds
        accountLabels = $labels
        enumeratedObjectIdentifiers = $false
        stateChangingResourceRequests = $false
        destructiveRequests = $false
        credentialValuesRecorded = $false
        authorizationHeadersRecorded = $false
        responseBodiesRecorded = $false
        tokensRecorded = $false
        objectIdentifiersRecorded = $false
        locationValuesRecorded = $false
    }
    summary = [ordered]@{
        caseCount = $caseResults.Count
        requestCount = $allRequests.Count
        passCount = @($caseResults | Where-Object { $_.disposition -eq "PASS" }).Count
        candidateViolationCount = @(
            $caseResults | Where-Object { $_.disposition -eq "CANDIDATE_VIOLATION" }
        ).Count
        inconclusiveCount = @(
            $caseResults | Where-Object { $_.disposition -eq "INCONCLUSIVE" }
        ).Count
    }
    cases = $caseResults
}

$evidencePath = Join-Path $resolvedOutputDirectory "$runId.json"
$evidence | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $evidencePath -Encoding UTF8

Write-Host "Run ID: $runId"
Write-Host "Sanitized evidence: $evidencePath"
Write-Host (
    "Summary: pass={0}, candidate={1}, inconclusive={2}" -f `
        $evidence.summary.passCount, `
        $evidence.summary.candidateViolationCount, `
        $evidence.summary.inconclusiveCount
)
