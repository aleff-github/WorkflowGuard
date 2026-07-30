[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Manifest,

    [switch]$RequireActive
)

$ErrorActionPreference = "Stop"
$errors = [System.Collections.Generic.List[string]]::new()
$warnings = [System.Collections.Generic.List[string]]::new()

function Add-ValidationError {
    param([string]$Message)
    $errors.Add($Message)
}

function Get-Text {
    param(
        [object]$Value,
        [string]$Field
    )
    $text = [string]$Value
    if ([string]::IsNullOrWhiteSpace($text)) {
        Add-ValidationError "$Field must not be blank."
    }
    return $text
}

function Parse-UtcInstant {
    param(
        [object]$Value,
        [string]$Field
    )
    $parsed = [DateTimeOffset]::MinValue
    if (-not [DateTimeOffset]::TryParse(
        [string]$Value,
        [Globalization.CultureInfo]::InvariantCulture,
        [Globalization.DateTimeStyles]::AssumeUniversal,
        [ref]$parsed
    )) {
        Add-ValidationError "$Field must be an ISO-8601 timestamp."
        return $null
    }
    return $parsed.ToUniversalTime()
}

try {
    $resolvedManifest = (Resolve-Path -LiteralPath $Manifest -ErrorAction Stop).Path
    $document = Get-Content -LiteralPath $resolvedManifest -Raw -Encoding utf8 |
        ConvertFrom-Json
} catch {
    [ordered]@{
        valid = $false
        activationReady = $false
        errors = @("Unable to read engagement manifest: " + $_.Exception.Message)
        warnings = @()
    } | ConvertTo-Json -Depth 8
    exit 2
}

if ($document.schemaVersion -ne 1) {
    Add-ValidationError "schemaVersion must be 1."
}

$engagementId = Get-Text $document.engagementId "engagementId"
if ($engagementId -and $engagementId -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]{2,63}$') {
    Add-ValidationError "engagementId contains unsupported characters."
}

$validStatuses = @("DRAFT", "APPROVED", "REVOKED")
$status = [string]$document.status
if ($validStatuses -notcontains $status) {
    Add-ValidationError "status must be DRAFT, APPROVED, or REVOKED."
}

if (@("staging", "production") -notcontains [string]$document.environment) {
    Add-ValidationError "environment must be staging or production."
}

$owner = Get-Text $document.authorization.owner "authorization.owner"
$reference = Get-Text $document.authorization.reference "authorization.reference"
$notBefore = Parse-UtcInstant $document.authorization.notBefore "authorization.notBefore"
$notAfter = Parse-UtcInstant $document.authorization.notAfter "authorization.notAfter"
if ($notBefore -and $notAfter -and $notAfter -le $notBefore) {
    Add-ValidationError "authorization.notAfter must be later than notBefore."
}

$origins = @($document.scope.allowedOrigins)
if ($origins.Count -eq 0) {
    Add-ValidationError "scope.allowedOrigins must contain at least one exact origin."
}
$normalizedOrigins = [System.Collections.Generic.List[string]]::new()
$hasReservedPlaceholder = $false
foreach ($originValue in $origins) {
    $originText = [string]$originValue
    $uri = $null
    if (-not [Uri]::TryCreate($originText, [UriKind]::Absolute, [ref]$uri)) {
        Add-ValidationError "Invalid allowed origin: $originText"
        continue
    }
    if (@("http", "https") -notcontains $uri.Scheme) {
        Add-ValidationError "Only HTTP(S) origins are supported: $originText"
    }
    if ([string]::IsNullOrWhiteSpace($uri.Host) -or
        -not [string]::IsNullOrWhiteSpace($uri.UserInfo) -or
        -not [string]::IsNullOrWhiteSpace($uri.Query) -or
        -not [string]::IsNullOrWhiteSpace($uri.Fragment) -or
        ($uri.AbsolutePath -ne "/")) {
        Add-ValidationError "Origins must contain only scheme, host, and optional port: $originText"
    }
    if ($uri.Host -match '[*?]') {
        Add-ValidationError "Wildcard hosts are not allowed: $originText"
    }
    if ($uri.Scheme -eq "http" -and -not $uri.IsLoopback) {
        $warnings.Add("Unencrypted non-loopback origin: $originText")
    }
    if ($uri.Host.EndsWith(".invalid", [StringComparison]::OrdinalIgnoreCase)) {
        $hasReservedPlaceholder = $true
    }
    $normalizedOrigins.Add($uri.GetLeftPart([UriPartial]::Authority))
}
if (($normalizedOrigins | Select-Object -Unique).Count -ne $normalizedOrigins.Count) {
    Add-ValidationError "scope.allowedOrigins contains duplicate origins."
}

foreach ($prefix in @($document.scope.excludedPathPrefixes)) {
    if (-not ([string]$prefix).StartsWith("/")) {
        Add-ValidationError "Every excluded path prefix must start with '/': $prefix"
    }
}
if ($document.scope.allowRedirects -ne $false) {
    Add-ValidationError "scope.allowRedirects must be false."
}

$allowedHttpMethods = @("GET", "HEAD", "OPTIONS", "POST", "PUT", "PATCH", "DELETE")
$methods = @($document.scope.allowedMethods)
if ($methods.Count -eq 0) {
    Add-ValidationError "scope.allowedMethods must contain at least one method."
}
foreach ($method in $methods) {
    if ($allowedHttpMethods -notcontains [string]$method) {
        Add-ValidationError "Unsupported HTTP method: $method"
    }
}

$identities = @($document.identities)
if ($identities.Count -eq 0) {
    Add-ValidationError "At least one non-secret identity reference is required."
}
foreach ($identity in $identities) {
    Get-Text $identity.label "identities.label" | Out-Null
    $burpReference = Get-Text $identity.burpProjectReference "identities.burpProjectReference"
    if ($burpReference -match '(?i)(cookie\s*:|authorization\s*:|bearer\s+|password\s*=|session\s*=)') {
        Add-ValidationError "Identity references must not contain credentials or HTTP secrets."
    }
}

$execution = $document.execution
if (@("DRY_RUN", "ACTIVE") -notcontains [string]$execution.mode) {
    Add-ValidationError "execution.mode must be DRY_RUN or ACTIVE."
}
if ([int]$execution.maxCases -lt 1 -or [int]$execution.maxCases -gt 20) {
    Add-ValidationError "execution.maxCases must be between 1 and 20."
}
if ([int]$execution.maxRequestsPerCase -lt 1 -or
    [int]$execution.maxRequestsPerCase -gt 50) {
    Add-ValidationError "execution.maxRequestsPerCase must be between 1 and 50."
}
if ([int]$execution.delayMilliseconds -lt 500 -or
    [int]$execution.delayMilliseconds -gt 60000) {
    Add-ValidationError "execution.delayMilliseconds must be between 500 and 60000."
}
if ([int]$execution.concurrency -ne 1) {
    Add-ValidationError "execution.concurrency must remain 1."
}
if ($execution.stateChangingApproved -eq $true -and
    $execution.cleanupRequired -ne $true) {
    Add-ValidationError "State-changing tests require cleanupRequired=true."
}
$stateChangingMethods = @("POST", "PUT", "PATCH", "DELETE")
if (($methods | Where-Object { $stateChangingMethods -contains $_ }).Count -gt 0 -and
    $execution.stateChangingApproved -ne $true) {
    Add-ValidationError "State-changing methods require stateChangingApproved=true."
}

if ([string]$document.evidence.outputDirectory -ne "agy/output") {
    Add-ValidationError "evidence.outputDirectory must be agy/output."
}
if ([int]$document.evidence.retentionDays -lt 1 -or
    [int]$document.evidence.retentionDays -gt 90) {
    Add-ValidationError "evidence.retentionDays must be between 1 and 90."
}

$now = [DateTimeOffset]::UtcNow
$activationReady = $errors.Count -eq 0 -and
    $status -eq "APPROVED" -and
    [string]$execution.mode -eq "ACTIVE" -and
    -not [string]::IsNullOrWhiteSpace([string]$document.authorization.approvedBy) -and
    $null -ne $document.authorization.approvedAt -and
    $notBefore -and
    $notAfter -and
    $now -ge $notBefore -and
    $now -le $notAfter

if ($status -eq "REVOKED") {
    $warnings.Add("The engagement is revoked; target traffic is prohibited.")
}
if ([string]$execution.mode -eq "DRY_RUN") {
    $warnings.Add("DRY_RUN permits planning and local validation only.")
}
if ($status -ne "APPROVED") {
    $warnings.Add("Only APPROVED engagements can become activation-ready.")
}
if ($notBefore -and $now -lt $notBefore) {
    $warnings.Add("The authorization window has not started.")
}
if ($notAfter -and $now -gt $notAfter) {
    $warnings.Add("The authorization window has expired.")
}
if ($hasReservedPlaceholder) {
    $warnings.Add("Reserved .invalid origins are placeholders and cannot be activated.")
    $activationReady = $false
}
if ($RequireActive -and -not $activationReady) {
    Add-ValidationError "The engagement is not activation-ready."
}

$manifestHash = (Get-FileHash -LiteralPath $resolvedManifest -Algorithm SHA256).Hash
[ordered]@{
    valid = $errors.Count -eq 0
    activationReady = [bool]$activationReady
    engagementId = $engagementId
    status = $status
    mode = [string]$execution.mode
    manifestSha256 = $manifestHash
    allowedOrigins = @($normalizedOrigins)
    limits = [ordered]@{
        maxCases = [int]$execution.maxCases
        maxRequestsPerCase = [int]$execution.maxRequestsPerCase
        delayMilliseconds = [int]$execution.delayMilliseconds
        concurrency = [int]$execution.concurrency
    }
    errors = @($errors)
    warnings = @($warnings)
} | ConvertTo-Json -Depth 8

if ($errors.Count -gt 0) {
    exit 2
}
