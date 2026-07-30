[CmdletBinding()]
param(
    [ValidateRange(1, 65535)]
    [int]$ProxyPort = 8080,

    [ValidateRange(0, 10000)]
    [int]$DelayMilliseconds = 750,

    [string]$EvidenceDirectory
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

$repositoryRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if (-not $EvidenceDirectory) {
    $EvidenceDirectory = Join-Path `
        $repositoryRoot `
        "agy\output\workflowguard-local-owasp-lab-2026"
}

$proxyUri = "http://127.0.0.1:$ProxyPort"
$listener = Get-NetTCPConnection `
    -LocalAddress "127.0.0.1" `
    -LocalPort $ProxyPort `
    -State Listen `
    -ErrorAction SilentlyContinue
if (-not $listener) {
    throw "No Burp proxy listener is active at $proxyUri."
}

$startedAt = [DateTimeOffset]::UtcNow
$runId = "guest-baseline-$($startedAt.ToString('yyyyMMddTHHmmssZ'))"
$targets = @(
    [pscustomobject]@{
        Name = "OWASP Juice Shop"
        Uri = "http://127.0.0.1:3000/"
    },
    [pscustomobject]@{
        Name = "OWASP crAPI"
        Uri = "http://127.0.0.1:8888/"
    }
)

$proxy = [WorkflowGuardForcedProxy]::new([uri]$proxyUri)
$handler = [System.Net.Http.HttpClientHandler]::new()
$handler.Proxy = $proxy
$handler.UseProxy = $true
$client = [System.Net.Http.HttpClient]::new($handler)
$client.Timeout = [TimeSpan]::FromSeconds(15)
$client.DefaultRequestHeaders.UserAgent.ParseAdd("WorkflowGuard-Lab/0.3")
$client.DefaultRequestHeaders.Add("X-WorkflowGuard-Test-Run", $runId)

try {
    Write-Host "Sending guest baseline through Burp proxy $proxyUri"
    Write-Host "Run ID: $runId"

    $results = foreach ($target in $targets) {
        $response = $client.GetAsync($target.Uri).GetAwaiter().GetResult()
        try {
            [pscustomobject]@{
                Target = $target.Name
                Uri = $target.Uri
                Method = "GET"
                Status = [int]$response.StatusCode
                ViaBurpProcess = $listener.OwningProcess
                RunId = $runId
            }
        } finally {
            $response.Dispose()
        }

        if ($DelayMilliseconds -gt 0) {
            Start-Sleep -Milliseconds $DelayMilliseconds
        }
    }

    $failures = @($results | Where-Object { $_.Status -ne 200 })
    $results | Format-Table -AutoSize Target, Uri, Status, ViaBurpProcess
    if ($failures.Count -gt 0) {
        throw "$($failures.Count) guest baseline request(s) returned a non-200 status."
    }

    New-Item -ItemType Directory -Path $EvidenceDirectory -Force | Out-Null
    $evidencePath = Join-Path $EvidenceDirectory "$runId.json"
    $evidence = [ordered]@{
        schemaVersion = 1
        runId = $runId
        startedAt = $startedAt.ToString("o")
        completedAt = [DateTimeOffset]::UtcNow.ToString("o")
        scope = "loopback-only"
        proxy = [ordered]@{
            uri = $proxyUri
            owningProcess = $listener.OwningProcess
        }
        safety = [ordered]@{
            authenticated = $false
            stateChangingRequests = 0
            concurrency = 1
            delayMilliseconds = $DelayMilliseconds
            responseBodiesRecorded = $false
        }
        results = @($results)
    }
    $evidence |
        ConvertTo-Json -Depth 5 |
        Set-Content -LiteralPath $evidencePath -Encoding utf8

    Write-Host "Guest baseline completed. Filter Burp Proxy history by:"
    Write-Host "X-WorkflowGuard-Test-Run: $runId"
    Write-Host "Sanitized evidence: $evidencePath"
} finally {
    $client.Dispose()
    $handler.Dispose()
}
