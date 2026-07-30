$ErrorActionPreference = "Stop"

function Write-Decision {
    param(
        [Parameter(Mandatory = $true)]
        [ValidateSet("allow", "deny", "ask", "force_ask")]
        [string]$Decision,

        [Parameter(Mandatory = $true)]
        [string]$Reason
    )

    [ordered]@{
        decision = $Decision
        reason = $Reason
    } | ConvertTo-Json -Compress
}

function Get-ArgumentPath {
    param(
        [object]$Arguments,
        [string[]]$Names
    )

    foreach ($name in $Names) {
        $property = $Arguments.PSObject.Properties[$name]
        if ($null -ne $property -and
            -not [string]::IsNullOrWhiteSpace([string]$property.Value)) {
            return [string]$property.Value
        }
    }
    return $null
}

function Test-PathWithinRoots {
    param(
        [string]$Candidate,
        [string[]]$Roots,
        [string]$BaseRoot
    )

    if ([string]::IsNullOrWhiteSpace($Candidate) -or $Roots.Count -eq 0) {
        return $false
    }
    if ([string]::IsNullOrWhiteSpace($BaseRoot)) {
        $BaseRoot = [string]$Roots[0]
    }
    if ($Candidate.StartsWith("~")) {
        return $false
    }

    try {
        if ([IO.Path]::IsPathRooted($Candidate)) {
            $candidatePath = [IO.Path]::GetFullPath($Candidate)
        } else {
            $candidatePath = [IO.Path]::GetFullPath(
                (Join-Path $BaseRoot $Candidate)
            )
        }

        foreach ($root in $Roots) {
            $rootPath = [IO.Path]::GetFullPath([string]$root).TrimEnd(
                [IO.Path]::DirectorySeparatorChar,
                [IO.Path]::AltDirectorySeparatorChar
            )
            if ($candidatePath.Equals(
                $rootPath,
                [StringComparison]::OrdinalIgnoreCase
            ) -or $candidatePath.StartsWith(
                $rootPath + [IO.Path]::DirectorySeparatorChar,
                [StringComparison]::OrdinalIgnoreCase
            )) {
                return $true
            }
        }
    } catch {
        return $false
    }
    return $false
}

try {
    $rawInput = [Console]::In.ReadToEnd()
    if ([string]::IsNullOrWhiteSpace($rawInput)) {
        Write-Decision -Decision "deny" -Reason "WorkflowGuard gate received no tool-call data."
        exit 0
    }

    $payload = $rawInput | ConvertFrom-Json
    $toolName = [string]$payload.toolCall.name
    $arguments = $payload.toolCall.args
    $workspacePaths = @(
        $payload.workspacePaths |
            ForEach-Object { [string]$_ } |
            Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
    )
    if ($workspacePaths.Count -eq 0 -and
        -not [string]::IsNullOrWhiteSpace($env:WORKFLOWGUARD_AGY_WORKSPACE)) {
        try {
            $fallbackRoot = [IO.Path]::GetFullPath(
                $env:WORKFLOWGUARD_AGY_WORKSPACE
            )
            $requiredMarkers = @(
                (Join-Path $fallbackRoot "agy\engagements\active.json"),
                (Join-Path $fallbackRoot ".agents\plugins\workflowguard-qa\plugin.json")
            )
            if ([IO.Path]::IsPathRooted($env:WORKFLOWGUARD_AGY_WORKSPACE) -and
                (Test-Path -LiteralPath $fallbackRoot -PathType Container) -and
                @($requiredMarkers | Where-Object {
                    -not (Test-Path -LiteralPath $_ -PathType Leaf)
                }).Count -eq 0) {
                $workspacePaths = @($fallbackRoot)
            }
        } catch {
            $workspacePaths = @()
        }
    }
    if ([string]::IsNullOrWhiteSpace($toolName)) {
        Write-Decision -Decision "deny" -Reason "WorkflowGuard gate could not identify the proposed tool."
        exit 0
    }

    if ($workspacePaths.Count -eq 0) {
        Write-Decision -Decision "deny" -Reason "WorkflowGuard gate received no workspace boundary."
        exit 0
    }

    if ($toolName -eq "list_permissions") {
        Write-Decision -Decision "allow" -Reason "Reading the current permission state is allowed."
        exit 0
    }

    $readOnlyTools = @(
        "view_file",
        "list_dir",
        "find_by_name",
        "grep_search"
    )
    if ($readOnlyTools -contains $toolName) {
        $readRoots = @($workspacePaths)
        $installedPluginRoot = Join-Path `
            $env:USERPROFILE `
            ".gemini\config\plugins\workflowguard-qa"
        if (Test-Path -LiteralPath $installedPluginRoot -PathType Container) {
            $readRoots += $installedPluginRoot
        }
        $readPath = Get-ArgumentPath -Arguments $arguments -Names @(
            "AbsolutePath",
            "DirectoryPath",
            "SearchDirectory",
            "SearchPath",
            "Path"
        )
        if (-not (Test-PathWithinRoots -Candidate $readPath -Roots $readRoots -BaseRoot ([string]$workspacePaths[0]))) {
            Write-Decision -Decision "deny" -Reason "Read-only inspection is restricted to the current WorkflowGuard workspace and installed QA plugin."
            exit 0
        }
        Write-Decision -Decision "allow" -Reason "Read-only inspection inside the workspace is allowed."
        exit 0
    }

    $writeTools = @(
        "write_to_file",
        "replace_file_content",
        "multi_replace_file_content"
    )
    if ($writeTools -contains $toolName) {
        $writePath = Get-ArgumentPath -Arguments $arguments -Names @(
            "TargetFile",
            "AbsolutePath",
            "Path"
        )
        $outputRoot = Join-Path ([string]$workspacePaths[0]) "agy\output"
        if (-not (Test-PathWithinRoots -Candidate $writePath -Roots @($outputRoot) -BaseRoot ([string]$workspacePaths[0]))) {
            Write-Decision -Decision "deny" -Reason "The QA agent may write only sanitized evidence under agy/output."
            exit 0
        }
        Write-Decision -Decision "force_ask" -Reason "Writing sanitized evidence requires explicit human review."
        exit 0
    }

    if ($toolName -eq "run_command") {
        $commandLine = [string]$arguments.CommandLine
        $workingDirectory = Get-ArgumentPath -Arguments $arguments -Names @(
            "Cwd",
            "WorkingDirectory",
            "DirectoryPath"
        )
        if (-not [string]::IsNullOrWhiteSpace($workingDirectory) -and
            -not (Test-PathWithinRoots -Candidate $workingDirectory -Roots $workspacePaths -BaseRoot ([string]$workspacePaths[0]))) {
            Write-Decision -Decision "deny" -Reason "Commands may run only inside the current workspace."
            exit 0
        }
        $allowedPatterns = @(
            '^\s*(\.\\)?gradlew\.bat\s+(clean\s+)?test(\s+jar)?\s*$',
            '^\s*git\s+status(\s+(--short|--porcelain(=v1)?|--branch)){0,2}\s*$',
            '^\s*git\s+diff(\s+(--check|--stat|--cached))?\s*$',
            '^\s*powershell(\.exe)?\s+-NoProfile\s+-ExecutionPolicy\s+Bypass\s+-File\s+["'']?scripts[\\/]agy[\\/]Validate-WorkflowGuardEngagement\.ps1["'']?\s+-Manifest\s+["'']?agy[\\/]engagements[\\/](active|engagement\.example)\.json["'']?(\s+-RequireActive)?\s*$',
            '^\s*powershell(\.exe)?\s+-NoProfile\s+-ExecutionPolicy\s+Bypass\s+-File\s+["'']?scripts[\\/]agy[\\/]Test-WorkflowGuardAgyConfiguration\.ps1["'']?\s*$'
        )
        foreach ($pattern in $allowedPatterns) {
            if ($commandLine -match $pattern) {
                Write-Decision -Decision "force_ask" -Reason "The command is locally allowlisted but still requires explicit approval."
                exit 0
            }
        }

        Write-Decision -Decision "deny" -Reason "Arbitrary shell commands and network clients are disabled for the WorkflowGuard QA agent."
        exit 0
    }

    $interactiveTools = @("ask_question")
    if ($interactiveTools -contains $toolName) {
        Write-Decision -Decision "allow" -Reason "Human clarification is allowed."
        exit 0
    }

    $blockedPatterns = @(
        '^browser_',
        '^mcp',
        '^search_web$',
        '^read_url_content$',
        '^invoke_subagent$',
        '^define_subagent$',
        '^send_message$',
        '^manage_subagents$',
        '^schedule$',
        '^manage_task$',
        '^ask_permission$',
        '^generate_image$'
    )
    foreach ($pattern in $blockedPatterns) {
        if ($toolName -match $pattern) {
            Write-Decision -Decision "deny" -Reason "Network, delegation, scheduling, MCP, and permission-escalation tools are disabled."
            exit 0
        }
    }

    Write-Decision -Decision "deny" -Reason "Unknown tools are denied by default."
} catch {
    Write-Decision -Decision "deny" -Reason ("WorkflowGuard gate failed closed: " + $_.Exception.Message)
}
