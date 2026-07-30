[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$JuiceCredentialFile,

    [Parameter(Mandatory = $true)]
    [string]$CrapiCredentialFile,

    [string]$ProxyUrl = "http://127.0.0.1:8080",

    [string]$OutputDirectory = "agy/output/workflowguard-local-owasp-lab-2026",

    [ValidateRange(750, 60000)]
    [int]$DelayMilliseconds = 750
)

$ErrorActionPreference = "Stop"
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

function Read-LabCredentialFile {
    param(
        [Parameter(Mandatory = $true)]
        [string]$LiteralPath,

        [Parameter(Mandatory = $true)]
        [string]$Target,

        [Parameter(Mandatory = $true)]
        [string[]]$RequiredFields
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

        if ($parsed[$currentLabel].Contains($normalizedKey)) {
            throw "Duplicate credential field '$normalizedKey' for target '$Target', account '$currentLabel'."
        }

        $parsed[$currentLabel][$normalizedKey] = $value
    }

    $accounts = @()
    foreach ($label in @("A", "B", "C", "D")) {
        if (-not $parsed.Contains($label)) {
            throw "Missing account label '$label' for target '$Target'."
        }

        foreach ($field in $RequiredFields) {
            if (-not $parsed[$label].Contains($field)) {
                throw "Missing credential field '$field' for target '$Target', account '$label'."
            }
        }

        $email = [string]$parsed[$label]["email"]
        try {
            $mailAddress = [System.Net.Mail.MailAddress]::new($email)
        }
        catch {
            throw "Invalid email format for target '$Target', account '$label'."
        }

        if ($mailAddress.Address -ne $email) {
            throw "Invalid email format for target '$Target', account '$label'."
        }

        $accounts += [pscustomobject]@{
            Label = $label
            Email = $email
            Password = [string]$parsed[$label]["password"]
        }
    }

    $uniqueEmails = @($accounts | ForEach-Object { $_.Email.ToLowerInvariant() } | Sort-Object -Unique)
    if ($uniqueEmails.Count -ne $accounts.Count) {
        throw "Credential emails are not unique for target '$Target'."
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

function Invoke-SanitizedLogin {
    param(
        [Parameter(Mandatory = $true)]
        [string]$RunId,

        [Parameter(Mandatory = $true)]
        [string]$Target,

        [Parameter(Mandatory = $true)]
        [uri]$Endpoint,

        [Parameter(Mandatory = $true)]
        [pscustomobject]$Account,

        [Parameter(Mandatory = $true)]
        [uri]$Proxy
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
        $request = [System.Net.Http.HttpRequestMessage]::new(
            [System.Net.Http.HttpMethod]::Post,
            $Endpoint
        )
        $request.Headers.Add("X-WorkflowGuard-Test-Run", $RunId)
        $request.Headers.Add("X-WorkflowGuard-Actor", "$Target-$($Account.Label.ToLowerInvariant())")
        $request.Headers.Accept.ParseAdd("application/json")

        $payload = @{
            email = $Account.Email
            password = $Account.Password
        } | ConvertTo-Json -Compress
        $request.Content = [System.Net.Http.StringContent]::new(
            $payload,
            [System.Text.Encoding]::UTF8,
            "application/json"
        )

        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        $responseBody = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        $statusCode = [int]$response.StatusCode
        $tokenPresent = $responseBody -match '(?i)"(?:token|access_token|accessToken)"\s*:\s*"[^"]+"'
        $accepted = $statusCode -ge 200 -and $statusCode -lt 300 -and $tokenPresent

        return [pscustomobject]@{
            target = $Target
            accountLabel = $Account.Label
            method = "POST"
            path = $Endpoint.AbsolutePath
            statusCode = $statusCode
            tokenPresent = [bool]$tokenPresent
            accepted = [bool]$accepted
            errorType = $null
        }
    }
    catch {
        return [pscustomobject]@{
            target = $Target
            accountLabel = $Account.Label
            method = "POST"
            path = $Endpoint.AbsolutePath
            statusCode = $null
            tokenPresent = $false
            accepted = $false
            errorType = $_.Exception.GetType().FullName
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
    }
}

Add-Type -AssemblyName System.Net.Http

$proxyUri = [uri]$ProxyUrl
Test-ProxyListener -Uri $proxyUri

$juiceAccounts = Read-LabCredentialFile `
    -LiteralPath $JuiceCredentialFile `
    -Target "juice-shop" `
    -RequiredFields @("email", "password", "securityAnswer")
$crapiAccounts = Read-LabCredentialFile `
    -LiteralPath $CrapiCredentialFile `
    -Target "crapi" `
    -RequiredFields @("email", "password", "phoneNumber")

$runId = "auth-baseline-$([DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ'))"
$targets = @(
    [pscustomobject]@{
        Name = "juice-shop"
        Endpoint = [uri]"http://127.0.0.1:3000/rest/user/login"
        Accounts = $juiceAccounts
        CredentialFile = $JuiceCredentialFile
    },
    [pscustomobject]@{
        Name = "crapi"
        Endpoint = [uri]"http://127.0.0.1:8888/identity/api/auth/login"
        Accounts = $crapiAccounts
        CredentialFile = $CrapiCredentialFile
    }
)

$results = @()
foreach ($target in $targets) {
    foreach ($account in $target.Accounts) {
        $result = Invoke-SanitizedLogin `
            -RunId $runId `
            -Target $target.Name `
            -Endpoint $target.Endpoint `
            -Account $account `
            -Proxy $proxyUri
        $results += $result

        $statusLabel = if ($null -eq $result.statusCode) { "transport-error" } else { [string]$result.statusCode }
        Write-Host ("{0} account {1}: HTTP {2}, token={3}, accepted={4}" -f `
                $result.target, `
                $result.accountLabel, `
                $statusLabel, `
                $result.tokenPresent.ToString().ToLowerInvariant(), `
                $result.accepted.ToString().ToLowerInvariant())

        Start-Sleep -Milliseconds $DelayMilliseconds
    }
}

$resolvedOutputDirectory = if ([System.IO.Path]::IsPathRooted($OutputDirectory)) {
    $OutputDirectory
}
else {
    Join-Path (Get-Location) $OutputDirectory
}
New-Item -ItemType Directory -Path $resolvedOutputDirectory -Force | Out-Null

$evidence = [ordered]@{
    schemaVersion = 1
    runId = $runId
    generatedAtUtc = [DateTime]::UtcNow.ToString("o")
    purpose = "Validate four pre-provisioned local-lab accounts per target through the Burp listener."
    safety = [ordered]@{
        environment = "local-authorized-lab"
        proxy = $ProxyUrl
        sequential = $true
        delayMilliseconds = $DelayMilliseconds
        attemptedInvalidCredentials = $false
        credentialValuesRecorded = $false
        responseBodiesRecorded = $false
        tokensRecorded = $false
        destructiveRequests = $false
    }
    credentialSources = @(
        [ordered]@{
            target = "juice-shop"
            fileName = [System.IO.Path]::GetFileName($JuiceCredentialFile)
            sha256 = (Get-FileHash -LiteralPath $JuiceCredentialFile -Algorithm SHA256).Hash
            accountCount = $juiceAccounts.Count
        },
        [ordered]@{
            target = "crapi"
            fileName = [System.IO.Path]::GetFileName($CrapiCredentialFile)
            sha256 = (Get-FileHash -LiteralPath $CrapiCredentialFile -Algorithm SHA256).Hash
            accountCount = $crapiAccounts.Count
        }
    )
    summary = [ordered]@{
        requestCount = $results.Count
        acceptedCount = @($results | Where-Object { $_.accepted }).Count
        failedCount = @($results | Where-Object { -not $_.accepted }).Count
    }
    results = $results
}

$evidencePath = Join-Path $resolvedOutputDirectory "$runId-accounts.json"
$evidence | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $evidencePath -Encoding UTF8

Write-Host "Run ID: $runId"
Write-Host "Sanitized evidence: $evidencePath"

if ($evidence.summary.failedCount -gt 0) {
    throw "One or more lab accounts failed sanitized authentication validation. See the redacted evidence."
}

Write-Host "All eight lab accounts were accepted through Burp."
