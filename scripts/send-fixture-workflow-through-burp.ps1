[CmdletBinding()]
param(
    [ValidateRange(1, 65535)]
    [int]$FixturePort = 18080,

    [ValidateRange(1, 65535)]
    [int]$ProxyPort = 8080
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Net.Http

$baseUri = "http://127.0.0.1:$FixturePort"
$proxyUri = "http://127.0.0.1:$ProxyPort"
$proxy = [System.Net.WebProxy]::new($proxyUri, $false)
$handler = [System.Net.Http.HttpClientHandler]::new()
$handler.Proxy = $proxy
$handler.UseProxy = $true
$client = [System.Net.Http.HttpClient]::new($handler)

function Invoke-FixtureRequest {
    param(
        [Parameter(Mandatory)]
        [string]$Method,

        [Parameter(Mandatory)]
        [string]$Path,

        [string]$Body
    )

    $hasBody = $PSBoundParameters.ContainsKey("Body")
    $request = [System.Net.Http.HttpRequestMessage]::new(
        [System.Net.Http.HttpMethod]::new($Method),
        "$baseUri$Path"
    )
    if ($hasBody) {
        $request.Content = [System.Net.Http.StringContent]::new(
            $Body,
            [System.Text.Encoding]::UTF8,
            "application/json"
        )
    }

    try {
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        try {
            $responseBody = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            [pscustomobject]@{
                Method = $Method
                Path = $Path
                Status = [int]$response.StatusCode
                Body = $responseBody
            }
        } finally {
            $response.Dispose()
        }
    } finally {
        $request.Dispose()
    }
}

try {
    Write-Host "Sending the fixture workflow through Burp proxy $proxyUri"

    $reset = Invoke-FixtureRequest -Method POST -Path "/test/reset"
    if ($reset.Status -ne 204) {
        throw "Fixture reset failed with HTTP $($reset.Status)."
    }

    $create = Invoke-FixtureRequest `
        -Method POST `
        -Path "/api/invitations" `
        -Body '{"recipient":"member-b"}'
    if ($create.Status -ne 201) {
        throw "Invitation creation failed with HTTP $($create.Status)."
    }
    $invitation = $create.Body | ConvertFrom-Json

    $accept = Invoke-FixtureRequest `
        -Method POST `
        -Path "/api/invitations/$($invitation.id)/accept"
    $revoke = Invoke-FixtureRequest -Method DELETE -Path "/api/members/member-b"
    $beforeReplay = Invoke-FixtureRequest `
        -Method GET `
        -Path "/api/organizations/org-1/members"
    $replay = Invoke-FixtureRequest `
        -Method POST `
        -Path "/api/invitations/$($invitation.id)/accept"
    $afterReplay = Invoke-FixtureRequest `
        -Method GET `
        -Path "/api/organizations/org-1/members"

    $beforeState = $beforeReplay.Body | ConvertFrom-Json
    $afterState = $afterReplay.Body | ConvertFrom-Json
    $replayResult = $replay.Body | ConvertFrom-Json

    @($create, $accept, $revoke, $beforeReplay, $replay, $afterReplay) |
        Select-Object Method, Path, Status |
        Format-Table -AutoSize

    Write-Host "Invitation ID: $($invitation.id)"
    Write-Host "Members before replay: $($beforeState.count)"
    Write-Host "Replay stateChanged: $($replayResult.stateChanged)"
    Write-Host "Members after replay: $($afterState.count)"
    Write-Host ""
    Write-Host "The requests are now available in Burp Proxy history."
} finally {
    $client.Dispose()
    $handler.Dispose()
}
