[CmdletBinding()]
param(
  [switch] $MetadataOnly,
  [switch] $DocumentationOnly,
  [switch] $StaticOnly,
  [switch] $SkipBuild,
  [string] $JarPath,
  [string] $VelocityHome
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ($MetadataOnly -and ($DocumentationOnly -or $StaticOnly -or $SkipBuild)) {
  throw "MetadataOnly cannot be combined with DocumentationOnly, StaticOnly, or SkipBuild"
}
if ($DocumentationOnly -and ($StaticOnly -or $SkipBuild)) {
  throw "DocumentationOnly cannot be combined with StaticOnly or SkipBuild"
}
if ($StaticOnly -and $SkipBuild) {
  throw "StaticOnly and SkipBuild cannot be used together"
}

$Root = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
if ([string]::IsNullOrWhiteSpace($JarPath)) {
  $JarPath = Join-Path $Root "starx-plugins\starx-velocity\build\libs\starx-velocity.jar"
} elseif (-not [System.IO.Path]::IsPathRooted($JarPath)) {
  $JarPath = Join-Path $Root $JarPath
}
$JarPath = [System.IO.Path]::GetFullPath($JarPath)
if ([string]::IsNullOrWhiteSpace($VelocityHome)) {
  $VelocityHome = Join-Path $Root "velocity-test"
} elseif (-not [System.IO.Path]::IsPathRooted($VelocityHome)) {
  $VelocityHome = Join-Path $Root $VelocityHome
}
$VelocityHome = [System.IO.Path]::GetFullPath($VelocityHome)

$Failures = [System.Collections.Generic.List[string]]::new()

function Add-GateFailure([string] $Message) {
  $Failures.Add($Message)
  Write-Host "FAIL: $Message" -ForegroundColor Red
}

function Get-MarkdownCodeBlocks(
  [string] $Text,
  [string[]] $Languages,
  [int] $BaseOffset = 0
) {
  $AcceptedLanguages = [System.Collections.Generic.HashSet[string]]::new(
    [System.StringComparer]::OrdinalIgnoreCase)
  foreach ($Language in $Languages) {
    [void] $AcceptedLanguages.Add($Language)
  }
  $AcceptAllLanguages = @($Languages).Count -eq 0

  $Lines = @([regex]::Matches(
    $Text,
    '(?m)^(?<content>[^\r\n]*)(?<newline>\r?\n|$)') | Where-Object {
      $_.Length -gt 0
    })
  $Blocks = [System.Collections.Generic.List[object]]::new()
  $LineIndex = 0
  while ($LineIndex -lt $Lines.Count) {
    $OpenLine = $Lines[$LineIndex]
    $Open = [regex]::Match(
      $OpenLine.Groups["content"].Value,
      '^(?<indent> {0,3})(?<fence>`{3,}|~{3,})(?<info>[^\r\n]*)$')
    if (-not $Open.Success) {
      $LineIndex++
      continue
    }

    $Fence = $Open.Groups["fence"].Value
    $Info = $Open.Groups["info"].Value
    if ($Fence[0] -eq [char]96 -and $Info.Contains('`')) {
      $LineIndex++
      continue
    }

    $Info = $Info.Trim()
    $Language = if ($Info.Length -eq 0) {
      ""
    } else {
      [regex]::Match($Info, '^\S+').Value
    }
    $ClosingLineIndex = -1
    for ($CandidateIndex = $LineIndex + 1;
        $CandidateIndex -lt $Lines.Count;
        $CandidateIndex++) {
      $Close = [regex]::Match(
        $Lines[$CandidateIndex].Groups["content"].Value,
        '^(?<indent> {0,3})(?<fence>`+|~+)[ \t]*$')
      if (-not $Close.Success) {
        continue
      }
      $ClosingFence = $Close.Groups["fence"].Value
      if ($ClosingFence[0] -eq $Fence[0] -and
          $ClosingFence.Length -ge $Fence.Length) {
        $ClosingLineIndex = $CandidateIndex
        break
      }
    }

    $Closed = $ClosingLineIndex -ge 0
    $CodeStart = $OpenLine.Index + $OpenLine.Length
    $CodeEnd = if ($Closed) {
      $Lines[$ClosingLineIndex].Index
    } else {
      $Text.Length
    }
    if ($AcceptAllLanguages -or $AcceptedLanguages.Contains($Language)) {
      $BlockEnd = if ($Closed) {
        $Lines[$ClosingLineIndex].Index + $Lines[$ClosingLineIndex].Length
      } else {
        $Text.Length
      }
      $Blocks.Add([pscustomobject]@{
        Language = $Language
        InfoString = $Info
        Code = $Text.Substring($CodeStart, $CodeEnd - $CodeStart)
        StartOffset = $BaseOffset + $OpenLine.Index
        EndOffset = $BaseOffset + $BlockEnd
        Closed = $Closed
      })
    }

    if (-not $Closed) {
      break
    }
    $LineIndex = $ClosingLineIndex + 1
  }
  return @($Blocks)
}

function Get-MarkedCodeBlock(
  [string] $Text,
  [string] $Marker,
  [string[]] $Languages
) {
  $Open = "<!-- $Marker -->"
  $Close = "<!-- /$Marker -->"
  $Pattern = '(?is)' + [regex]::Escape($Open) +
    '(?<body>.*?)' + [regex]::Escape($Close)
  $Regions = @([regex]::Matches($Text, $Pattern))
  if ($Regions.Count -ne 1) {
    return $null
  }
  $Body = $Regions[0].Groups["body"]
  $Blocks = @(Get-MarkdownCodeBlocks $Body.Value $Languages $Body.Index)
  if ($Blocks.Count -ne 1 -or -not $Blocks[0].Closed) {
    return $null
  }
  return $Blocks[0]
}

function Get-MarkedTextSection(
  [string] $Text,
  [string] $Marker
) {
  $Open = "<!-- $Marker -->"
  $Close = "<!-- /$Marker -->"
  $Pattern = '(?is)' + [regex]::Escape($Open) +
    '(?<body>.*?)' + [regex]::Escape($Close)
  $Regions = @([regex]::Matches($Text, $Pattern))
  if ($Regions.Count -ne 1) {
    return $null
  }
  return $Regions[0].Groups["body"].Value.Trim()
}

function Convert-KeyValueSection([string] $Text) {
  $Values = @{}
  if ([string]::IsNullOrWhiteSpace($Text)) {
    return $Values
  }
  foreach ($Line in $Text -split '\r?\n') {
    $Match = [regex]::Match($Line.Trim(), '^(?<key>[A-Za-z0-9_.-]+)=(?<value>.*)$')
    if (-not $Match.Success) {
      continue
    }
    $Values[$Match.Groups["key"].Value] = $Match.Groups["value"].Value.Trim()
  }
  return $Values
}

function Test-UworldEvidencePlaceholder([string] $Value) {
  if ([string]::IsNullOrWhiteSpace($Value)) {
    return $true
  }
  return $Value.Trim() -match '(?i)^(none|not-recorded|unverified|pending|placeholder|n/?a|-|ok|pass(?:ed)?|success|true|yes|done)$'
}

function Test-UworldDoctorEvidence(
  [string] $Evidence,
  [string] $CandidateSha,
  [string] $InstalledSha,
  [string] $ExpectedVelocityHome,
  [string] $ExpectedInstalledPath
) {
  $EvidenceMatch = [regex]::Match(
    $Evidence,
    '^(?<path>[^;]+);\s*sha256=(?<sha>[A-Fa-f0-9]{64})$'
  )
  if (-not $EvidenceMatch.Success) {
    return $false
  }

  $RelativePath = $EvidenceMatch.Groups["path"].Value.Trim()
  if ([System.IO.Path]::IsPathRooted($RelativePath)) {
    return $false
  }
  try {
    $EvidencePath = [System.IO.Path]::GetFullPath((Join-Path $Root $RelativePath))
  } catch {
    return $false
  }

  $PathComparison = if (
    [System.IO.Path]::DirectorySeparatorChar -ne
      [System.IO.Path]::AltDirectorySeparatorChar
  ) {
    [System.StringComparison]::OrdinalIgnoreCase
  } else {
    [System.StringComparison]::Ordinal
  }
  $RootBoundary = $Root.TrimEnd(
    [System.IO.Path]::DirectorySeparatorChar,
    [System.IO.Path]::AltDirectorySeparatorChar) +
    [System.IO.Path]::DirectorySeparatorChar
  if (-not $EvidencePath.StartsWith($RootBoundary, $PathComparison) -or
      -not (Test-Path -LiteralPath $EvidencePath -PathType Leaf)) {
    return $false
  }

  $ActualEvidenceSha = (Get-FileHash -LiteralPath $EvidencePath -Algorithm SHA256).Hash
  if (-not [string]::Equals(
      $ActualEvidenceSha,
      $EvidenceMatch.Groups["sha"].Value,
      [System.StringComparison]::OrdinalIgnoreCase)) {
    return $false
  }

  $Lines = @([System.IO.File]::ReadAllText($EvidencePath) -split '\r?\n' |
    ForEach-Object { $_.TrimEnd() } |
    Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
  $RequiredChecks = @(
    "velocity_home",
    "java_21",
    "velocity_build_606",
    "plugin_jar_inspection",
    "starx_jar_count",
    "external_limboapi",
    "candidate_hash",
    "starx_config_syntax",
    "uworld_config",
    "velocity_config_syntax",
    "target_registered",
    "backend_reachable",
    "velocity_modern_forwarding",
    "paper_config_syntax",
    "paper_velocity_forwarding",
    "forwarding_secret_match",
    "forwarding_online_mode_match",
    "paper_server_properties_syntax",
    "paper_online_mode",
    "paper_target_port",
    "paper_target_host",
    "paper_server_binding",
    "sqlite_parent_writable"
  )
  if ($Lines.Count -ne $RequiredChecks.Count + 1 -or
      $Lines[-1] -cne "UWORLD_ENVIRONMENT=PASS") {
    return $false
  }

  $Checks = @{}
  for ($LineIndex = 0; $LineIndex -lt $Lines.Count - 1; $LineIndex++) {
    $Line = $Lines[$LineIndex]
    $CheckMatch = [regex]::Match(
      $Line,
      '^CHECK name=(?<name>[a-z0-9_]+) status=(?<status>PASS|FAIL) detail=(?<detail>.+)$'
    )
    if (-not $CheckMatch.Success -or
        $CheckMatch.Groups["status"].Value -cne "PASS" -or
        $Checks.ContainsKey($CheckMatch.Groups["name"].Value)) {
      return $false
    }
    $Checks[$CheckMatch.Groups["name"].Value] = $CheckMatch.Groups["detail"].Value
  }
  foreach ($CheckName in $RequiredChecks) {
    if (-not $Checks.ContainsKey($CheckName)) {
      return $false
    }
  }

  $ExpectedVelocityDetail = "path=$ExpectedVelocityHome exists=true"
  $ExpectedInstalledDetail = "single=true path=$ExpectedInstalledPath"
  $ExpectedHashDetail = "candidate_sha256=$CandidateSha deployed_sha256=$InstalledSha match=true"
  return [string]::Equals(
      [string] $Checks["velocity_home"],
      $ExpectedVelocityDetail,
      $PathComparison) -and
    [string]::Equals(
      [string] $Checks["starx_jar_count"],
      $ExpectedInstalledDetail,
      $PathComparison) -and
    [string]::Equals(
      [string] $Checks["candidate_hash"],
      $ExpectedHashDetail,
      [System.StringComparison]::OrdinalIgnoreCase)
}

function Get-JunitSummary([string] $ProjectRoot) {
  $ResultDirectory = Join-Path $ProjectRoot "build\test-results\test"
  if (-not (Test-Path -LiteralPath $ResultDirectory -PathType Container)) {
    return $null
  }
  $Files = @(Get-ChildItem -LiteralPath $ResultDirectory -File -Filter "TEST-*.xml")
  if ($Files.Count -eq 0) {
    return $null
  }

  $Summary = [ordered]@{
    Suites = 0L
    Tests = 0L
    Failures = 0L
    Errors = 0L
    Skipped = 0L
  }
  try {
    foreach ($File in $Files) {
      [xml] $Document = [System.IO.File]::ReadAllText($File.FullName)
      $Suites = @($Document.SelectNodes("//testsuite"))
      if ($Suites.Count -eq 0) {
        return $null
      }
      foreach ($Suite in $Suites) {
        $Summary.Suites++
        foreach ($Name in @("tests", "failures", "errors", "skipped")) {
          $Attribute = $Suite.Attributes[$Name]
          [long] $Value = 0
          if ($null -ne $Attribute -and
              -not [long]::TryParse($Attribute.Value, [ref] $Value)) {
            return $null
          }
          $Target = switch ($Name) {
            "tests" { "Tests" }
            "failures" { "Failures" }
            "errors" { "Errors" }
            "skipped" { "Skipped" }
          }
          $Summary[$Target] += $Value
        }
      }
    }
  } catch {
    return $null
  }
  return [pscustomobject] $Summary
}

function Test-DocumentPattern(
  [string] $Text,
  [string] $Pattern,
  [string] $Failure
) {
  if ($Text -notmatch $Pattern) {
    Add-GateFailure $Failure
    return $false
  }
  return $true
}

function Parse-PowerShellCode([string] $Code, [string] $Label) {
  $Tokens = $null
  $Errors = $null
  $Ast = [System.Management.Automation.Language.Parser]::ParseInput(
    $Code,
    [ref] $Tokens,
    [ref] $Errors
  )
  if ($Errors.Count -ne 0) {
    Add-GateFailure "$Label contains invalid PowerShell syntax"
    return $null
  }
  return $Ast
}

function Get-ContainingFunction([System.Management.Automation.Language.Ast] $Node) {
  $Current = $Node.Parent
  while ($null -ne $Current) {
    if ($Current -is [System.Management.Automation.Language.FunctionDefinitionAst]) {
      return $Current
    }
    $Current = $Current.Parent
  }
  return $null
}

function Test-IsRootPowerShellCommand(
  [System.Management.Automation.Language.CommandAst] $Command
) {
  if ($Command.Parent -isnot [System.Management.Automation.Language.PipelineAst]) {
    return $false
  }
  $Block = $Command.Parent.Parent
  return $Block -is [System.Management.Automation.Language.NamedBlockAst] -and
    $Block.Parent -is [System.Management.Automation.Language.ScriptBlockAst] -and
    $null -eq $Block.Parent.Parent
}

function Test-IsRootPowerShellStatement(
  [System.Management.Automation.Language.StatementAst] $Statement
) {
  return $Statement.Parent -is
      [System.Management.Automation.Language.NamedBlockAst] -and
    $Statement.Parent.Parent -is
      [System.Management.Automation.Language.ScriptBlockAst] -and
    $null -eq $Statement.Parent.Parent.Parent
}

function Get-TopLevelCommands([System.Management.Automation.Language.Ast] $Ast) {
  return @($Ast.FindAll({
    param($Node)
    $Node -is [System.Management.Automation.Language.CommandAst]
  }, $true) | Where-Object {
    Test-IsRootPowerShellCommand $_
  })
}

function Test-PowerShellPathQualifiedName([string] $Name) {
  if ([string]::IsNullOrWhiteSpace($Name)) {
    return $false
  }
  return [System.IO.Path]::IsPathRooted($Name) -or
    $Name.Contains('/') -or
    $Name.StartsWith('.\') -or
    $Name.StartsWith('./')
}

function Get-PowerShellLeafName([string] $Name) {
  if ([string]::IsNullOrWhiteSpace($Name)) {
    return $null
  }
  $Parts = @($Name -split '[\\/]')
  return $Parts[-1]
}

function Get-PowerShellScopedLeafName([string] $Name) {
  if ([string]::IsNullOrWhiteSpace($Name)) {
    return $null
  }
  $Parts = @($Name -split ':')
  return $Parts[-1]
}

function Get-NormalizedPowerShellName([string] $Name) {
  if ([string]::IsNullOrWhiteSpace($Name)) {
    return $null
  }
  $ModuleQualified = [regex]::Match(
    $Name,
    '^(?<module>[A-Za-z_][A-Za-z0-9_.-]*)\\(?<command>[^\\/]+)$')
  if ($ModuleQualified.Success -and
      -not [System.IO.Path]::IsPathRooted($Name)) {
    $Module = $ModuleQualified.Groups['module'].Value
    $Command = $ModuleQualified.Groups['command'].Value
    $TrustedModuleCommands = @{
      'Microsoft.PowerShell.Management' = @(
        'Resolve-Path',
        'Get-ChildItem',
        'Get-Item',
        'Get-Content',
        'Test-Path',
        'Copy-Item',
        'Move-Item',
        'Stop-Service',
        'Start-Service'
      )
      'Microsoft.PowerShell.Utility' = @('Get-FileHash')
    }
    if ($TrustedModuleCommands.ContainsKey($Module) -and
        $TrustedModuleCommands[$Module] -contains $Command) {
      return $Command
    }
    return $Name
  }
  if (Test-PowerShellPathQualifiedName $Name) {
    return $Name
  }
  if ($Name -ieq 'icacls.exe') {
    return 'icacls'
  }
  return $Name
}

function Get-NormalizedPowerShellCommandName(
  [System.Management.Automation.Language.CommandAst] $Command
) {
  return Get-NormalizedPowerShellName ($Command.GetCommandName())
}

function Get-PowerShellAliasTargetName(
  [System.Management.Automation.Language.CommandAst] $Command
) {
  $Name = Get-NormalizedPowerShellCommandName $Command
  if ([string]::IsNullOrWhiteSpace($Name)) {
    return $null
  }
  $Alias = @(Get-Alias -Name $Name -ErrorAction SilentlyContinue |
    Select-Object -First 1)
  if ($Alias.Count -ne 1) {
    return $null
  }
  return Get-NormalizedPowerShellName $Alias[0].Definition
}

function Get-PowerShellStaticBinding(
  [System.Management.Automation.Language.CommandAst] $Command
) {
  try {
    $Binding = [System.Management.Automation.Language.StaticParameterBinder]::BindCommand(
      $Command,
      $true)
    if ($Binding.BindingExceptions.Count -ne 0) {
      return $null
    }
    return $Binding
  } catch {
    return $null
  }
}

function Get-PowerShellBoundParameterNames(
  [System.Management.Automation.Language.CommandAst] $Command
) {
  $Binding = Get-PowerShellStaticBinding $Command
  if ($null -eq $Binding) {
    return @()
  }
  return @($Binding.BoundParameters.Keys)
}

function Test-PowerShellBoundParameterValue(
  [System.Management.Automation.Language.CommandAst] $Command,
  [string] $Parameter,
  [string] $Expected
) {
  $Value = Get-PowerShellBoundParameterText $Command $Parameter
  return $null -ne $Value -and $Value -ceq $Expected
}

function Get-PowerShellBoundParameterText(
  [System.Management.Automation.Language.CommandAst] $Command,
  [string] $Parameter
) {
  $Binding = Get-PowerShellStaticBinding $Command
  if ($null -eq $Binding -or
      -not $Binding.BoundParameters.ContainsKey($Parameter)) {
    return $null
  }
  $Value = $Binding.BoundParameters[$Parameter].Value
  if ($null -eq $Value) {
    return $null
  }
  return $Value.Extent.Text
}

function Test-PowerShellExplicitFalseSwitch(
  [System.Management.Automation.Language.CommandAst] $Command,
  [string] $Parameter
) {
  $Matches = @($Command.CommandElements | Where-Object {
    $_ -is [System.Management.Automation.Language.CommandParameterAst] -and
    $_.ParameterName -ieq $Parameter
  })
  return $Matches.Count -eq 1 -and
    $null -ne $Matches[0].Argument -and
    $Matches[0].Argument.Extent.Text -ceq '$false'
}

function Test-PowerShellDeploymentParameterSet(
  [System.Management.Automation.Language.CommandAst] $Command,
  [string[]] $Required
) {
  $Names = @(Get-PowerShellBoundParameterNames $Command)
  $RequiredNames = @($Required + @('ErrorAction'))
  foreach ($Parameter in $RequiredNames) {
    if ($Names -notcontains $Parameter) {
      return $false
    }
  }

  foreach ($Name in $Names) {
    if ($RequiredNames -notcontains $Name) {
      return $false
    }
  }
  $ErrorAction = Get-PowerShellBoundParameterText $Command 'ErrorAction'
  if ($ErrorAction -notmatch '(?i)^(?:Stop|[''"]Stop[''"])$') {
    return $false
  }
  foreach ($Switch in @('WhatIf', 'Confirm')) {
    if (-not (Test-PowerShellExplicitFalseSwitch $Command $Switch)) {
      return $false
    }
  }
  return $true
}

function Test-PowerShellSingleArgument(
  [System.Management.Automation.Language.CommandAst] $Command,
  [string] $Expected
) {
  $Elements = @($Command.CommandElements)
  return $Elements.Count -eq 2 -and
    $Elements[1].Extent.Text -ceq $Expected
}

function Test-SafeFileHashPipeline(
  [System.Management.Automation.Language.CommandAst] $Command
) {
  if ($Command.Parent -isnot [System.Management.Automation.Language.PipelineAst]) {
    return $false
  }
  $Elements = @($Command.Parent.PipelineElements)
  $CommandIndex = -1
  for ($Index = 0; $Index -lt $Elements.Count; $Index++) {
    if ([object]::ReferenceEquals($Elements[$Index], $Command)) {
      $CommandIndex = $Index
      break
    }
  }
  if ($CommandIndex -le 0) {
    return $false
  }

  $SourceElement = $Elements[$CommandIndex - 1]
  $Source = if ($SourceElement -is
      [System.Management.Automation.Language.CommandAst]) {
    $SourceElement
  } elseif ($SourceElement -is
      [System.Management.Automation.Language.CommandExpressionAst] -and
      $SourceElement.Expression -is
        [System.Management.Automation.Language.ParenExpressionAst]) {
    $InnerElements = @($SourceElement.Expression.Pipeline.PipelineElements)
    if ($InnerElements.Count -eq 1 -and
        $InnerElements[0] -is
          [System.Management.Automation.Language.CommandAst]) {
      $InnerElements[0]
    } else {
      $null
    }
  } else {
    $null
  }
  if ($null -eq $Source) {
    return $false
  }
  $SourceName = Get-NormalizedPowerShellCommandName $Source
  if ($SourceName -notin @('Get-Item', 'Get-ChildItem')) {
    return $false
  }
  $SourceParameters = @(Get-PowerShellBoundParameterNames $Source)
  return $SourceParameters -contains 'LiteralPath'
}

function Test-PowerShellCommandSafety(
  [System.Management.Automation.Language.Ast] $Ast,
  [string] $Label
) {
  $FilesystemCommands = @(
    "Resolve-Path",
    "Get-FileHash",
    "Get-ChildItem",
    "Get-Item",
    "Get-Content",
    "Test-Path",
    "Copy-Item",
    "Move-Item"
  )
  $ProtectedFunctions = @($FilesystemCommands + @(
    'Stop-Service',
    'Start-Service',
    'icacls'
  ))
  $FunctionDefinitions = @($Ast.FindAll({
    param($Node)
    $Node -is [System.Management.Automation.Language.FunctionDefinitionAst]
  }, $true))
  foreach ($FunctionDefinition in $FunctionDefinitions) {
    $FunctionName = Get-PowerShellScopedLeafName $FunctionDefinition.Name
    if ($ProtectedFunctions -contains $FunctionName) {
      Add-GateFailure "$Label must not shadow protected command $($FunctionDefinition.Name)"
    }
  }
  $Commands = @($Ast.FindAll({
    param($Node)
    $Node -is [System.Management.Automation.Language.CommandAst]
  }, $true))
  foreach ($Command in $Commands) {
    $RawName = $Command.GetCommandName()
    if ((Test-PowerShellPathQualifiedName $RawName) -and
        (Get-PowerShellLeafName $RawName) -match '(?i)^icacls(?:\.exe)?$') {
      Add-GateFailure "$Label must not invoke path-qualified icacls"
      continue
    }
    $Name = Get-NormalizedPowerShellCommandName $Command
    if ([string]::IsNullOrWhiteSpace($Name)) {
      if ($null -eq (Get-ContainingFunction $Command)) {
        Add-GateFailure "$Label must not use top-level dynamic command invocation"
      }
      continue
    }
    $AliasTarget = Get-PowerShellAliasTargetName $Command
    if ($FilesystemCommands -contains $AliasTarget) {
      Add-GateFailure "$Label must not use filesystem alias $Name"
      continue
    }
    if ($FilesystemCommands -contains $Name) {
      $BoundParameters = @(Get-PowerShellBoundParameterNames $Command)
      $UsesLiteralPath = $BoundParameters -contains 'LiteralPath'
      $UsesInputStream = $Name -ieq 'Get-FileHash' -and
        $BoundParameters -contains 'InputStream'
      $UsesSafePipeline = $Name -ieq 'Get-FileHash' -and
        $BoundParameters -notcontains 'Path' -and
        (Test-SafeFileHashPipeline $Command)
      if (-not $UsesLiteralPath -and
          -not $UsesInputStream -and
          -not $UsesSafePipeline) {
        Add-GateFailure "$Label command $Name must use -LiteralPath"
      }
    }
    if ($Name -ieq "icacls") {
      $Function = Get-ContainingFunction $Command
      if ($null -eq $Function -or $Function.Name -ine "Invoke-Icacls") {
        Add-GateFailure "$Label must not invoke icacls outside Invoke-Icacls"
      }
    }
  }
}

function Test-IcaclsFailureStatement(
  [System.Management.Automation.Language.StatementAst] $Statement
) {
  if ($Statement -is [System.Management.Automation.Language.ThrowStatementAst]) {
    return $true
  }
  return $Statement -is [System.Management.Automation.Language.ExitStatementAst] -and
    $Statement.Extent.Text -match '(?i)^\s*exit\s+[1-9][0-9]*\s*$'
}

function Test-IcaclsHelper(
  [System.Management.Automation.Language.FunctionDefinitionAst] $Helper
) {
  $Parameters = @($Helper.Parameters)
  if ($Parameters.Count -ne 1 -or
      $Parameters[0].Name.VariablePath.UserPath -cne 'Arguments' -or
      $Parameters[0].Extent.Text -notmatch
        '(?i)^\s*\[string\[\]\]\s+\$Arguments\s*$' -or
      $null -ne $Helper.Body.BeginBlock -or
      $null -ne $Helper.Body.ProcessBlock -or
      $null -ne $Helper.Body.DynamicParamBlock -or
      $null -eq $Helper.Body.EndBlock) {
    return $false
  }
  $Statements = @($Helper.Body.EndBlock.Statements)
  if ($Statements.Count -ne 2) {
    return $false
  }
  $Invocation = $Statements[0]
  if ($Invocation -isnot [System.Management.Automation.Language.PipelineAst]) {
    return $false
  }
  $PipelineCommands = @($Invocation.PipelineElements | Where-Object {
    $_ -is [System.Management.Automation.Language.CommandAst]
  })
  $Icacls = @($PipelineCommands | Where-Object {
    (Get-NormalizedPowerShellCommandName $_) -ieq 'icacls' -and
    [object]::ReferenceEquals((Get-ContainingFunction $_), $Helper)
  })
  $UnexpectedCommands = @($PipelineCommands | Where-Object {
    (Get-NormalizedPowerShellCommandName $_) -notin @('icacls', 'Out-Null')
  })
  if ($Icacls.Count -ne 1 -or
      $UnexpectedCommands.Count -ne 0 -or
      -not (Test-PowerShellSingleArgument $Icacls[0] '@Arguments')) {
    return $false
  }

  $Guard = $Statements[1]
  if ($Guard -isnot [System.Management.Automation.Language.IfStatementAst] -or
      $Guard.Clauses.Count -ne 1 -or $null -ne $Guard.ElseClause) {
    return $false
  }
  $Clause = $Guard.Clauses[0]
  if ($Clause.Item1.Extent.Text -notmatch
      '(?i)^\s*\$LASTEXITCODE\s+-ne\s+0\s*$') {
    return $false
  }
  $FailureStatements = @($Clause.Item2.Statements)
  return $FailureStatements.Count -eq 1 -and
    (Test-IcaclsFailureStatement $FailureStatements[0])
}

function Get-PowerShellIcaclsArgumentKind(
  [System.Management.Automation.Language.CommandAst] $Command
) {
  $Names = @(Get-PowerShellBoundParameterNames $Command)
  if ($Names.Count -ne 1 -or $Names -notcontains 'Arguments') {
    return $null
  }
  $Arguments = Get-PowerShellBoundParameterText $Command 'Arguments'
  if ($null -eq $Arguments) {
    return $null
  }
  $Normalized = [regex]::Replace($Arguments, '\s+', ' ').Trim()
  if ($Normalized -match
      '(?i)^@\(\s*\$BackupRoot, [''"]/reset[''"](?:, [''"]/T[''"])?(?:, [''"]/C[''"])?\s*\)$') {
    return 'reset'
  }
  if ($Normalized -match
      '(?i)^@\(\s*\$BackupRoot, [''"]/inheritance:r[''"]\s*\)$') {
    return 'inheritance'
  }
  if ($Normalized -match
      '(?i)^@\(\s*\$BackupRoot, [''"]/grant:r[''"], [''"]\*S-1-5-18:\(OI\)\(CI\)F[''"], [''"]\*S-1-5-32-544:\(OI\)\(CI\)F[''"]\s*\)$') {
    return 'grant'
  }
  return $null
}

function Test-PowerShellDirectFunctionCommand(
  [System.Management.Automation.Language.CommandAst] $Command,
  [System.Management.Automation.Language.FunctionDefinitionAst] $Function
) {
  $Current = $Command.Parent
  while ($null -ne $Current -and
      -not [object]::ReferenceEquals($Current, $Function)) {
    if ($Current -is [System.Management.Automation.Language.IfStatementAst] -or
        $Current -is [System.Management.Automation.Language.LoopStatementAst] -or
        $Current -is [System.Management.Automation.Language.SwitchStatementAst] -or
        $Current -is [System.Management.Automation.Language.TrapStatementAst] -or
        $Current -is [System.Management.Automation.Language.ScriptBlockExpressionAst]) {
      return $false
    }
    $Current = $Current.Parent
  }
  return $null -ne $Current
}

function Get-PowerShellContainingAssignment(
  [System.Management.Automation.Language.Ast] $Node,
  [System.Management.Automation.Language.FunctionDefinitionAst] $Function
) {
  $Current = $Node.Parent
  while ($null -ne $Current -and
      -not [object]::ReferenceEquals($Current, $Function)) {
    if ($Current -is [System.Management.Automation.Language.AssignmentStatementAst]) {
      return $Current
    }
    $Current = $Current.Parent
  }
  return $null
}

function Test-PowerShellFunctionParameters(
  [System.Management.Automation.Language.FunctionDefinitionAst] $Function,
  [string[]] $Names
) {
  $Parameters = @($Function.Parameters)
  if ($Parameters.Count -ne $Names.Count) {
    return $false
  }
  for ($Index = 0; $Index -lt $Names.Count; $Index++) {
    if ($Parameters[$Index].Name.VariablePath.UserPath -cne $Names[$Index]) {
      return $false
    }
  }
  return $true
}

function Test-PowerShellExplicitArgument(
  [System.Management.Automation.Language.CommandAst] $Command,
  [string] $Name
) {
  $Matches = [System.Collections.Generic.List[string]]::new()
  $Elements = @($Command.CommandElements)
  for ($Index = 1; $Index -lt $Elements.Count; $Index++) {
    $Element = $Elements[$Index]
    if ($Element -isnot [System.Management.Automation.Language.CommandParameterAst] -or
        $Element.ParameterName -ine $Name) {
      continue
    }
    if ($null -ne $Element.Argument) {
      $Matches.Add($Element.Argument.Extent.Text)
      continue
    }
    if ($Index + 1 -ge $Elements.Count -or
        $Elements[$Index + 1] -is
          [System.Management.Automation.Language.CommandParameterAst]) {
      $Matches.Add("")
      continue
    }
    $Matches.Add($Elements[$Index + 1].Extent.Text)
  }
  if ($Matches.Count -ne 1) {
    return $false
  }
  $Value = $Matches[0].Trim()
  return -not [string]::IsNullOrWhiteSpace($Value) -and
    $Value -notmatch '^(?:\$null|''\s*''|"\s*")$'
}

function Test-IsPowerShellDoctorInvocation(
  [System.Management.Automation.Language.CommandAst] $Command
) {
  $Name = Get-PowerShellLeafName $Command.GetCommandName()
  if ($Name -notmatch '^(?i:pwsh|powershell)(?:\.exe)?$') {
    return $false
  }
  return $Command.Extent.Text -match
    '(?i)(?:check-uworld-environment\.ps1|(?:^|\s)-File\s+\$Doctor\b)'
}

function Test-PowerShellDoctorRunnerIdentity([string] $Text) {
  return $Text -match
    '(?s)(?:''-ServiceIdentity''|"-ServiceIdentity")\s*,\s*\$ServiceIdentity\b'
}

function Test-PowerShellDoctorRunner(
  [System.Management.Automation.Language.FunctionDefinitionAst] $Function
) {
  if (-not (Test-PowerShellFunctionParameters $Function @('Jar', 'RequireBackend'))) {
    return $false
  }
  $Text = $Function.Extent.Text
  foreach ($Required in @(
      "'-File'",
      '$Doctor',
      "'-VelocityHome'",
      '$VelocityHome',
      "'-CandidateJar'",
      '$Jar',
      "'-ServiceIdentity'",
      '$ServiceIdentity',
      "'-RequireBackend'",
      '$LASTEXITCODE',
      'ExitCode',
      'Lines')) {
    if (-not $Text.Contains($Required)) {
      return $false
    }
  }
  if (-not (Test-PowerShellDoctorRunnerIdentity $Text)) {
    return $false
  }
  $DynamicCalls = @($Function.FindAll({
    param($Node)
    $Node -is [System.Management.Automation.Language.CommandAst] -and
    $null -eq $Node.GetCommandName() -and
    $Node.CommandElements.Count -eq 2 -and
    $Node.CommandElements[0].Extent.Text -ceq '$PowerShell' -and
    $Node.CommandElements[1].Extent.Text -ceq '@DoctorArguments' -and
    (Test-PowerShellDirectFunctionCommand $Node $Function)
  }, $true))
  if ($DynamicCalls.Count -ne 1) {
    return $false
  }
  $OutputAssignment = Get-PowerShellContainingAssignment `
    $DynamicCalls[0] `
    $Function
  if ($null -eq $OutputAssignment -or
      $OutputAssignment.Left -isnot
        [System.Management.Automation.Language.VariableExpressionAst] -or
      $OutputAssignment.Left.VariablePath.UserPath -cne 'Output') {
    return $false
  }
  $ExitCaptures = @($Function.FindAll({
    param($Node)
    $Node -is [System.Management.Automation.Language.AssignmentStatementAst] -and
    $Node.Left -is [System.Management.Automation.Language.VariableExpressionAst] -and
    $Node.Left.VariablePath.UserPath -ceq 'ExitCode' -and
    $Node.Right.Extent.Text -match '(?i)^\s*\$LASTEXITCODE\s*$'
  }, $true))
  if ($ExitCaptures.Count -ne 1 -or
      $ExitCaptures[0].Extent.StartOffset -le
        $DynamicCalls[0].Extent.StartOffset) {
    return $false
  }
  $Returns = @($Function.FindAll({
    param($Node)
    $Node -is [System.Management.Automation.Language.ReturnStatementAst]
  }, $true))
  $Exits = @($Function.FindAll({
    param($Node)
    $Node -is [System.Management.Automation.Language.ExitStatementAst]
  }, $true))
  return $Returns.Count -eq 1 -and
    $Exits.Count -eq 0 -and
    $Returns[0].Extent.StartOffset -gt $ExitCaptures[0].Extent.StartOffset
}

function Test-PowerShellDoctorAssertion(
  [System.Management.Automation.Language.FunctionDefinitionAst] $Function,
  [bool] $RequireBackend
) {
  if (-not (Test-PowerShellFunctionParameters $Function @('Jar'))) {
    return $false
  }
  $ExpectedFlag = if ($RequireBackend) { '$true' } else { '$false' }
  $Calls = @($Function.FindAll({
    param($Node)
    $Node -is [System.Management.Automation.Language.CommandAst] -and
    (Get-NormalizedPowerShellCommandName $Node) -ieq 'Invoke-UworldDoctor' -and
    $Node.CommandElements.Count -eq 3 -and
    $Node.CommandElements[1].Extent.Text -ceq '$Jar' -and
    $Node.CommandElements[2].Extent.Text -ceq $ExpectedFlag -and
    (Test-PowerShellDirectFunctionCommand $Node $Function)
  }, $true))
  if ($Calls.Count -ne 1) {
    return $false
  }
  $ProbeAssignment = Get-PowerShellContainingAssignment $Calls[0] $Function
  if ($null -eq $ProbeAssignment -or
      $ProbeAssignment.Left -isnot
        [System.Management.Automation.Language.VariableExpressionAst] -or
      $ProbeAssignment.Left.VariablePath.UserPath -cne 'Probe') {
    return $false
  }
  $EarlyExits = @($Function.FindAll({
    param($Node)
    $Node -is [System.Management.Automation.Language.ReturnStatementAst] -or
      $Node -is [System.Management.Automation.Language.ExitStatementAst]
  }, $true))
  if ($EarlyExits.Count -ne 0) {
    return $false
  }
  $Throws = @($Function.FindAll({
    param($Node)
    $Node -is [System.Management.Automation.Language.ThrowStatementAst]
  }, $true))
  if ($Throws.Count -eq 0) {
    return $false
  }
  $Text = $Function.Extent.Text
  if ($RequireBackend) {
    return $Text.Contains('ExitCode') -and
      $Text.Contains('UWORLD_ENVIRONMENT=PASS')
  }
  foreach ($Check in @(
      'plugin_jar_inspection',
      'starx_jar_count',
      'external_limboapi',
      'candidate_hash')) {
    if (-not $Text.Contains($Check)) {
      return $false
    }
  }
  return $Text.Contains('status=PASS')
}

function Split-BashCommandSegments(
  [string] $Text,
  [int] $BaseOffset
) {
  $Segments = [System.Collections.Generic.List[object]]::new()
  $Quote = $null
  $Escaped = $false
  $SegmentStart = 0
  $OperatorBefore = $null
  for ($Index = 0; $Index -lt $Text.Length; $Index++) {
    $Character = $Text[$Index]
    if ($Quote -eq 'single') {
      if ($Character -eq "'") {
        $Quote = $null
      }
      continue
    }
    if ($Quote -eq 'double') {
      if ($Escaped) {
        $Escaped = $false
        continue
      }
      if ($Character -eq '\') {
        $Escaped = $true
        continue
      }
      if ($Character -eq '"') {
        $Quote = $null
      }
      continue
    }
    if ($Escaped) {
      $Escaped = $false
      continue
    }
    if ($Character -eq '\') {
      $Escaped = $true
      continue
    }
    if ($Character -eq "'") {
      $Quote = 'single'
      continue
    }
    if ($Character -eq '"') {
      $Quote = 'double'
      continue
    }

    $Operator = $null
    $SeparatorWidth = 0
    $Next = if ($Index + 1 -lt $Text.Length) {
      $Text[$Index + 1]
    } else {
      [char] 0
    }
    $Previous = if ($Index -gt 0) {
      $Text[$Index - 1]
    } else {
      [char] 0
    }
    if ($Character -eq ';') {
      $Operator = ';'
      $SeparatorWidth = 1
    } elseif ($Character -eq '&' -and $Next -eq '&') {
      $Operator = '&&'
      $SeparatorWidth = 2
    } elseif ($Character -eq '|' -and $Next -eq '|') {
      $Operator = '||'
      $SeparatorWidth = 2
    } elseif ($Character -eq '|' -and $Next -eq '&') {
      $Operator = '|&'
      $SeparatorWidth = 2
    } elseif ($Character -eq '|' -and $Previous -ne '>') {
      $Operator = '|'
      $SeparatorWidth = 1
    } elseif ($Character -eq '&' -and
        $Previous -notin @('>', '<') -and $Next -ne '>') {
      $Operator = '&'
      $SeparatorWidth = 1
    }
    if ($SeparatorWidth -eq 0) {
      continue
    }

    $Raw = $Text.Substring($SegmentStart, $Index - $SegmentStart)
    $Trimmed = $Raw.Trim()
    if ($Trimmed.Length -gt 0) {
      $Leading = $Raw.Length - $Raw.TrimStart().Length
      $Segments.Add([pscustomobject]@{
        Text = $Trimmed
        StartOffset = $BaseOffset + $SegmentStart + $Leading
        OperatorBefore = $OperatorBefore
        OperatorAfter = $Operator
      })
    }
    $OperatorBefore = $Operator
    $Index += $SeparatorWidth - 1
    $SegmentStart = $Index + 1
  }

  $Raw = $Text.Substring($SegmentStart)
  $Trimmed = $Raw.Trim()
  if ($Trimmed.Length -gt 0) {
    $Leading = $Raw.Length - $Raw.TrimStart().Length
    $Segments.Add([pscustomobject]@{
      Text = $Trimmed
      StartOffset = $BaseOffset + $SegmentStart + $Leading
      OperatorBefore = $OperatorBefore
      OperatorAfter = $null
    })
  }
  return @($Segments)
}

function Get-BashCompoundProbe([string] $Text) {
  $Probe = $Text.Trim()
  while ($Probe -match '^(?:then|else|do)\b\s*(?<rest>.*)$') {
    $Probe = $Matches['rest'].TrimStart()
  }
  while ($Probe -match '^(?:!|time(?:\s+-p)?)\s+(?<rest>.*)$') {
    $Probe = $Matches['rest'].TrimStart()
  }
  if ($Probe -match
      '^coproc(?:\s+[A-Za-z_][A-Za-z0-9_]*)?\s+(?<rest>[{(].*)$') {
    $Probe = $Matches['rest'].TrimStart()
  }
  return $Probe
}

function Get-BashStructuralMask([string] $Text) {
  $Mask = [System.Text.StringBuilder]::new()
  $Quote = $null
  $Escaped = $false
  for ($Index = 0; $Index -lt $Text.Length; $Index++) {
    $Character = $Text[$Index]
    if ($null -ne $Quote) {
      if ($Escaped) {
        [void] $Mask.Append(' ')
        $Escaped = $false
        continue
      }
      if ($Quote -in @('double', 'ansi') -and $Character -eq '\') {
        [void] $Mask.Append(' ')
        $Escaped = $true
        continue
      }
      $ClosesQuote = ($Quote -in @('single', 'ansi') -and
          $Character -eq "'") -or
        ($Quote -eq 'double' -and $Character -eq '"')
      if ($ClosesQuote) {
        $Quote = $null
      }
      [void] $Mask.Append(' ')
      continue
    }
    if ($Escaped) {
      [void] $Mask.Append(' ')
      $Escaped = $false
      continue
    }
    if ($Character -eq '\') {
      [void] $Mask.Append(' ')
      $Escaped = $true
      continue
    }
    if ($Character -eq '$' -and
        $Index + 1 -lt $Text.Length -and $Text[$Index + 1] -eq "'") {
      [void] $Mask.Append(' ')
      [void] $Mask.Append(' ')
      $Index++
      $Quote = 'ansi'
      continue
    }
    if ($Character -eq "'") {
      [void] $Mask.Append(' ')
      $Quote = 'single'
      continue
    }
    if ($Character -eq '"') {
      [void] $Mask.Append(' ')
      $Quote = 'double'
      continue
    }
    [void] $Mask.Append($Character)
  }
  return $Mask.ToString()
}

function Test-BashBraceGroupOpener([string] $Text) {
  $Probe = Get-BashCompoundProbe $Text
  $Mask = (Get-BashStructuralMask $Probe).TrimStart()
  return $Mask -match '^\{(?:\s|$)' -or
    $Mask -match '^[^;]*\)\s*\{(?:\s|$)'
}

function Test-BashControlOpener([string] $Text) {
  $Probe = Get-BashCompoundProbe $Text
  return $Probe -match '^(?:if|for|while|until|select|case)\b' -or
    (Test-BashBraceGroupOpener $Text) -or
    $Probe -match '^\((?!\()'
}

function Get-BashStructuralBraceDelta([string] $Text) {
  $Delta = 0
  $FunctionPattern =
    '^\s*(?:(?:function\s+)[A-Za-z_][A-Za-z0-9_-]*(?:\s*\(\s*\))?|' +
    '[A-Za-z_][A-Za-z0-9_-]*\s*\(\s*\))\s*\{(?:\s|$)'
  foreach ($Segment in @(Split-BashCommandSegments $Text 0)) {
    if ($Segment.Text -match $FunctionPattern -or
        (Test-BashBraceGroupOpener $Segment.Text)) {
      $Delta++
    }
    if ($Segment.Text -match '^\s*\}(?:\s|$|[<>])') {
      $Delta--
    }
  }
  return $Delta
}

function Get-BashWords([string] $Text) {
  $Words = [System.Collections.Generic.List[string]]::new()
  $Word = [System.Text.StringBuilder]::new()
  $Quote = $null
  $Escaped = $false
  $Started = $false
  foreach ($Character in $Text.ToCharArray()) {
    if ($Quote -eq 'single') {
      if ($Character -eq "'") {
        $Quote = $null
      } else {
        [void] $Word.Append($Character)
      }
      continue
    }
    if ($Quote -eq 'double') {
      if ($Escaped) {
        [void] $Word.Append($Character)
        $Escaped = $false
        continue
      }
      if ($Character -eq '\') {
        $Escaped = $true
        continue
      }
      if ($Character -eq '"') {
        $Quote = $null
      } else {
        [void] $Word.Append($Character)
      }
      continue
    }
    if ($Escaped) {
      [void] $Word.Append($Character)
      $Escaped = $false
      $Started = $true
      continue
    }
    if ($Character -eq '\') {
      $Escaped = $true
      $Started = $true
      continue
    }
    if ($Character -eq "'") {
      $Quote = 'single'
      $Started = $true
      continue
    }
    if ($Character -eq '"') {
      $Quote = 'double'
      $Started = $true
      continue
    }
    if ([char]::IsWhiteSpace($Character)) {
      if ($Started) {
        $Words.Add($Word.ToString())
        [void] $Word.Clear()
        $Started = $false
      }
      continue
    }
    [void] $Word.Append($Character)
    $Started = $true
  }
  if ($null -ne $Quote -or $Escaped) {
    return @()
  }
  if ($Started) {
    $Words.Add($Word.ToString())
  }
  return @($Words)
}

function Get-BashEffectiveCommandWords([string] $Text) {
  $Words = @(Get-BashWords (Convert-BashAnsiFragments $Text))
  $CommandIndex = 0
  while ($CommandIndex -lt $Words.Count -and
      $Words[$CommandIndex] -match '^[A-Za-z_][A-Za-z0-9_]*=') {
    $CommandIndex++
  }
  if ($CommandIndex -ge $Words.Count) {
    return @()
  }
  return @($Words[$CommandIndex..($Words.Count - 1)])
}

function Get-BashEffectiveCommandName([string] $Text) {
  $Words = @(Get-BashEffectiveCommandWords $Text)
  if ($Words.Count -eq 0) {
    return $null
  }
  if ($Words[0] -ieq 'builtin') {
    $Index = 1
    if ($Index -lt $Words.Count -and $Words[$Index] -ceq '--') {
      $Index++
    }
    if ($Index -ge $Words.Count) {
      return $null
    }
    return $Words[$Index]
  }
  if ($Words[0] -ine 'command') {
    return $Words[0]
  }

  $Index = 1
  while ($Index -lt $Words.Count -and $Words[$Index].StartsWith('-')) {
    $Option = $Words[$Index]
    if ($Option -ceq '--') {
      $Index++
      break
    }
    if ($Option -match '[vV]') {
      return $null
    }
    if ($Option -notmatch '^-p+$') {
      return $null
    }
    $Index++
  }
  if ($Index -ge $Words.Count) {
    return $null
  }
  return $Words[$Index]
}

function Test-IsBashDoctorInvocation([string] $Text) {
  $Name = Get-BashEffectiveCommandName $Text
  if ([string]::IsNullOrWhiteSpace($Name)) {
    return $false
  }
  $Name = @($Name -split '[\/]')[-1]
  if ($Name -notmatch '^(?i:pwsh|powershell)(?:\.exe)?$') {
    return $false
  }
  return $Text -match
    '(?i)(?:check-uworld-environment\.ps1|(?:^|\s)-File\s+["'']?\$UWORLD_DOCTOR\b)'
}

function Test-BashDoctorInvocationIdentity([string] $Text) {
  $Words = @(Get-BashEffectiveCommandWords $Text)
  $Matches = @()
  for ($Index = 0; $Index -lt $Words.Count; $Index++) {
    if ($Words[$Index] -ceq '-ServiceIdentity') {
      $Matches += $Index
    }
  }
  if ($Matches.Count -ne 1) {
    return $false
  }
  $ValueIndex = $Matches[0] + 1
  return $ValueIndex -lt $Words.Count -and
    -not [string]::IsNullOrWhiteSpace($Words[$ValueIndex]) -and
    -not $Words[$ValueIndex].StartsWith('-')
}

function Test-BashDoctorRunnerIdentity([string] $Code) {
  $Runner = Get-BashFunctionBody $Code 'run_uworld_doctor'
  if ($null -eq $Runner) {
    return $false
  }
  $Runner = Get-BashVisibleCode $Runner
  return $Runner -match
    '(?s)\blocal\s+args=\(.*?-ServiceIdentity\s+"\$VELOCITY_USER".*?\)'
}

function Test-BashSetInvocation([string] $Text) {
  return (Get-BashEffectiveCommandName $Text) -ieq 'set'
}

function Test-BashEvalInvocation([string] $Text) {
  return (Get-BashEffectiveCommandName $Text) -ieq 'eval'
}

function Test-BashCandidateInstall([string] $Text) {
  if ($Text -notmatch '^\s*install(?:\s|$)') {
    return $false
  }
  $Words = @(Get-BashEffectiveCommandWords $Text)
  if ($Words.Count -lt 3) {
    return $false
  }
  $CommandParts = @($Words[0] -split '[\\/]')
  if ($CommandParts[-1] -ine 'install') {
    return $false
  }
  $Source = $Words[$Words.Count - 2]
  $Destination = $Words[$Words.Count - 1]
  return $Source -ceq '$RELEASE_JAR' -and
    $Destination -in @(
      '$plugin_dir/starx-velocity.jar',
      '$VELOCITY_HOME/plugins/starx-velocity.jar')
}

function Test-BashCandidateMutation([string] $Text) {
  $Words = @(Get-BashEffectiveCommandWords $Text)
  if ($Words.Count -lt 2) {
    return $false
  }
  $CommandName = Get-BashEffectiveCommandName $Text
  $CommandParts = @($CommandName -split '[\\/]')
  return $CommandParts[-1] -ieq 'install' -and
    $Words -contains '$RELEASE_JAR'
}

function Convert-BashAnsiFragments([string] $Text) {
  $Evaluator = [System.Text.RegularExpressions.MatchEvaluator]{
    param([System.Text.RegularExpressions.Match] $Match)
    $Fragment = $Match.Groups['fragment'].Value
    $HexEvaluator = [System.Text.RegularExpressions.MatchEvaluator]{
      param([System.Text.RegularExpressions.Match] $HexMatch)
      return [char] [Convert]::ToInt32($HexMatch.Groups['hex'].Value, 16)
    }
    return [regex]::Replace(
      $Fragment,
      '\\x(?<hex>[0-9A-Fa-f]{2})',
      $HexEvaluator)
  }
  return [regex]::Replace(
    $Text,
    '\$\x27(?<fragment>(?:\\x[0-9A-Fa-f]{2}|[A-Za-z0-9_./-])+)\x27',
    $Evaluator)
}

function Test-BashPotentialCandidateInstall([string] $Code) {
  $Joined = [regex]::Replace($Code, '\\\r?\n', '')
  $Normalized = Convert-BashAnsiFragments $Joined
  $UsesReleaseSource = $Normalized -match
      '\$(?:RELEASE_JAR\b|\{RELEASE_JAR(?:\}|[^A-Za-z0-9_][^}]*\}))' -or
    $Normalized -match
      '(?i)(?<![A-Za-z0-9_$])(?:[A-Za-z0-9._-]+/)*releases?/(?:[A-Za-z0-9._-]+/)*starx-velocity\.jar(?:\s|["''`;|&]|$)'
  return $Normalized -match 'starx-velocity\.jar' -and
    $Normalized -match '(?i)(?<![A-Za-z0-9_])install(?![A-Za-z0-9_])' -and
    $UsesReleaseSource
}

function Get-BashFunctionBody([string] $Code, [string] $Name) {
  $Pattern = '(?ms)^' + [regex]::Escape($Name) +
    '\s*\(\s*\)\s*\{\s*\r?\n(?<body>.*?)^\}\s*(?:\r?\n|$)'
  $Matches = @([regex]::Matches($Code, $Pattern))
  if ($Matches.Count -ne 1) {
    return $null
  }
  return $Matches[0].Groups['body'].Value
}

function Get-BashVisibleCode([string] $Code) {
  $VisibleLines = [System.Collections.Generic.List[string]]::new()
  foreach ($Line in @($Code -split '\r?\n')) {
    $Mask = Get-BashStructuralMask $Line
    $CommentIndex = $Line.Length
    for ($Index = 0; $Index -lt $Mask.Length; $Index++) {
      if ($Mask[$Index] -ne '#') {
        continue
      }
      $StartsComment = $Index -eq 0 -or
        [char]::IsWhiteSpace($Mask[$Index - 1]) -or
        ';|&(){}'.Contains($Mask[$Index - 1])
      if ($StartsComment) {
        $CommentIndex = $Index
        break
      }
    }
    $VisibleLines.Add($Line.Substring(0, $CommentIndex).TrimEnd())
  }
  return $VisibleLines -join "`n"
}

function Test-BashDoctorDefinitions([string] $Code) {
  $Runner = Get-BashFunctionBody $Code 'run_uworld_doctor'
  $Identity = Get-BashFunctionBody $Code 'assert_uworld_jar_identity'
  $Environment = Get-BashFunctionBody $Code 'assert_uworld_environment'
  if ($null -eq $Runner -or $null -eq $Identity -or $null -eq $Environment) {
    return $false
  }
  $Runner = Get-BashVisibleCode $Runner
  $Identity = Get-BashVisibleCode $Identity
  $Environment = Get-BashVisibleCode $Environment
  $RunnerCommands = @(Get-BashTopLevelCommands $Runner)
  $IdentityCommands = @(Get-BashTopLevelCommands $Identity)
  $EnvironmentCommands = @(Get-BashTopLevelCommands $Environment)
  foreach ($Required in @(
      'pwsh',
      '-File',
      '$UWORLD_DOCTOR',
      '-VelocityHome',
      '$VELOCITY_HOME',
      '-CandidateJar',
      '$candidate',
      '-ServiceIdentity',
      '$VELOCITY_USER',
      '-RequireBackend',
      'UWORLD_DOCTOR_OUTPUT',
      'UWORLD_DOCTOR_CODE')) {
    if (-not $Runner.Contains($Required)) {
      return $false
    }
  }
  if (-not (Test-BashDoctorRunnerIdentity $Code)) {
    return $false
  }
  $RunnerInvocations = @($RunnerCommands | Where-Object {
    $_.Kind -eq 'Command' -and
    $_.ControlDepth -eq 1 -and
    $_.Text -match '^if\s+output="\$\(pwsh\b'
  })
  $RunnerEarlyExits = @($RunnerCommands | Where-Object {
    (Get-BashEffectiveCommandName $_.Text) -in @('return', 'exit')
  })
  if ($RunnerInvocations.Count -ne 1 -or $RunnerEarlyExits.Count -ne 0) {
    return $false
  }
  $IdentityCalls = @($IdentityCommands | Where-Object {
    (Test-BashStandaloneCommand $_) -and
    $_.Text -match '^run_uworld_doctor\s+"\$candidate"\s+0\s*$'
  })
  $IdentityBadReturns = @($IdentityCommands | Where-Object {
    (Get-BashEffectiveCommandName $_.Text) -eq 'return' -and
    $_.Text -notmatch '^return\s+1\s*$'
  })
  if ($IdentityCalls.Count -ne 1 -or
      $IdentityBadReturns.Count -ne 0 -or
      $Identity -notmatch 'status=PASS' -or
      $Identity -notmatch 'return\s+1') {
    return $false
  }
  foreach ($Check in @(
      'plugin_jar_inspection',
      'starx_jar_count',
      'external_limboapi',
      'candidate_hash')) {
    if (-not $Identity.Contains($Check)) {
      return $false
    }
  }
  $EnvironmentCalls = @($EnvironmentCommands | Where-Object {
    (Test-BashStandaloneCommand $_) -and
    $_.Text -match '^run_uworld_doctor\s+"\$candidate"\s+1\s*$'
  })
  $EnvironmentBadReturns = @($EnvironmentCommands | Where-Object {
    (Get-BashEffectiveCommandName $_.Text) -eq 'return' -and
    $_.Text -notmatch '^return\s+1\s*$'
  })
  return $EnvironmentCalls.Count -eq 1 -and
    $EnvironmentBadReturns.Count -eq 0 -and
    $Environment.Contains('UWORLD_DOCTOR_CODE') -and
    $Environment.Contains('UWORLD_ENVIRONMENT=PASS') -and
    $Environment -match 'return\s+1'
}

function Test-BashStandaloneCommand([object] $Command) {
  return $Command.ControlDepth -eq 0 -and
    $Command.OperatorBefore -in @($null, ';') -and
    $Command.OperatorAfter -in @($null, ';')
}

function Get-BashTopLevelCommands([string] $Code) {
  $Lines = @([regex]::Matches(
    $Code,
    '(?m)^(?<content>[^\r\n]*)(?<newline>\r?\n|$)') | Where-Object {
      $_.Length -gt 0
    })
  $Commands = [System.Collections.Generic.List[object]]::new()
  $HereDocs = [System.Collections.Generic.Queue[object]]::new()
  $Quote = $null
  $FunctionDepth = 0
  $PendingFunction = $false
  $ControlDepth = 0
  $CommandParts = [System.Collections.Generic.List[string]]::new()
  $CommandStart = -1

  foreach ($LineMatch in $Lines) {
    $Line = $LineMatch.Groups["content"].Value
    if ($HereDocs.Count -gt 0) {
      $HereDoc = $HereDocs.Peek()
      $DelimiterLine = if ($HereDoc.StripTabs) {
        $Line.TrimStart([char] 9)
      } else {
        $Line
      }
      if ($DelimiterLine -ceq $HereDoc.Delimiter) {
        [void] $HereDocs.Dequeue()
      }
      continue
    }

    $StartedInQuote = $null -ne $Quote
    $CommentIndex = $Line.Length
    $Escaped = $false
    for ($Position = 0; $Position -lt $Line.Length; $Position++) {
      $Character = $Line[$Position]
      if ($Quote -eq 'single') {
        if ($Character -eq "'") {
          $Quote = $null
        }
        continue
      }
      if ($Quote -eq 'double') {
        if ($Escaped) {
          $Escaped = $false
          continue
        }
        if ($Character -eq '\') {
          $Escaped = $true
          continue
        }
        if ($Character -eq '"') {
          $Quote = $null
        }
        continue
      }
      if ($Escaped) {
        $Escaped = $false
        continue
      }
      if ($Character -eq '\') {
        $Escaped = $true
        continue
      }
      if ($Character -eq "'") {
        $Quote = 'single'
        continue
      }
      if ($Character -eq '"') {
        $Quote = 'double'
        continue
      }

      $StartsComment = $Character -eq '#' -and (
        $Position -eq 0 -or
        [char]::IsWhiteSpace($Line[$Position - 1]) -or
        ';|&(){}'.Contains($Line[$Position - 1]))
      if ($StartsComment) {
        $CommentIndex = $Position
        break
      }

      if ($Character -eq '<' -and
          $Position + 1 -lt $Line.Length -and
          $Line[$Position + 1] -eq '<') {
        $Cursor = $Position + 2
        $StripTabs = $false
        if ($Cursor -lt $Line.Length -and $Line[$Cursor] -eq '-') {
          $StripTabs = $true
          $Cursor++
        }
        while ($Cursor -lt $Line.Length -and
            [char]::IsWhiteSpace($Line[$Cursor])) {
          $Cursor++
        }

        $UsedAnsiQuote = $false
        $UnsupportedDelimiter = $false
        $DelimiterBuilder = [System.Text.StringBuilder]::new()
        $DelimiterQuote = $null
        while ($Cursor -lt $Line.Length) {
          $DelimiterCharacter = $Line[$Cursor]
          if ($DelimiterQuote -in @('single', 'ansi')) {
            if ($DelimiterCharacter -eq "'") {
              $DelimiterQuote = $null
            } else {
              if ($DelimiterQuote -eq 'ansi' -and
                  $DelimiterCharacter -eq '\') {
                $UnsupportedDelimiter = $true
              }
              [void] $DelimiterBuilder.Append($DelimiterCharacter)
            }
            $Cursor++
            continue
          }
          if ($DelimiterQuote -eq 'double') {
            if ($DelimiterCharacter -eq '"') {
              $DelimiterQuote = $null
              $Cursor++
              continue
            }
            if ($DelimiterCharacter -eq '\') {
              if ($Cursor + 1 -ge $Line.Length) {
                $UnsupportedDelimiter = $true
                $Cursor++
                continue
              }
              $NextDelimiterCharacter = $Line[$Cursor + 1]
              if ($NextDelimiterCharacter -in @('$', '"', '\', [char] 96)) {
                [void] $DelimiterBuilder.Append($NextDelimiterCharacter)
                $Cursor += 2
                continue
              }
              [void] $DelimiterBuilder.Append($DelimiterCharacter)
              $Cursor++
              continue
            }
            [void] $DelimiterBuilder.Append($DelimiterCharacter)
            $Cursor++
            continue
          }
          if ([char]::IsWhiteSpace($DelimiterCharacter) -or
              ';|&<>()'.Contains($DelimiterCharacter)) {
            break
          }
          if ($DelimiterCharacter -eq '$' -and
              $Cursor + 1 -lt $Line.Length -and
              $Line[$Cursor + 1] -in @("'", '"')) {
            if ($Line[$Cursor + 1] -eq "'") {
              $DelimiterQuote = 'ansi'
              $UsedAnsiQuote = $true
            } else {
              $DelimiterQuote = 'double'
              $UnsupportedDelimiter = $true
            }
            $Cursor += 2
            continue
          }
          if ($DelimiterCharacter -eq '\' -and
              $Cursor + 1 -lt $Line.Length) {
            $Cursor++
            [void] $DelimiterBuilder.Append($Line[$Cursor])
            $Cursor++
            continue
          }
          if ($DelimiterCharacter -eq "'") {
            $DelimiterQuote = 'single'
            $Cursor++
            continue
          }
          if ($DelimiterCharacter -eq '"') {
            $DelimiterQuote = 'double'
            $Cursor++
            continue
          }
          [void] $DelimiterBuilder.Append($DelimiterCharacter)
          $Cursor++
        }
        $Delimiter = $DelimiterBuilder.ToString()
        if ($null -ne $DelimiterQuote -or
            ($Cursor -ge $Line.Length -and $Line.EndsWith('\')) -or
            ($UsedAnsiQuote -and $Delimiter -notmatch '^[A-Za-z0-9_]+$')) {
          $UnsupportedDelimiter = $true
        }
        if ($UnsupportedDelimiter) {
          Add-GateFailure "Bash code block contains unsupported heredoc delimiter"
        } elseif ($Delimiter.Length -gt 0) {
          $HereDocs.Enqueue([pscustomobject]@{
            Delimiter = $Delimiter
            StripTabs = $StripTabs
          })
        }
        $Position = [Math]::Max($Position, $Cursor - 1)
        continue
      }

    }

    $Visible = $Line.Substring(0, $CommentIndex).TrimEnd()
    if ($FunctionDepth -gt 0) {
      $FunctionDepth += Get-BashStructuralBraceDelta $Visible
      if ($FunctionDepth -le 0) {
        $FunctionDepth = 0
      }
      continue
    }
    if ($PendingFunction) {
      if ([string]::IsNullOrWhiteSpace($Visible)) {
        continue
      }
      if ($Visible -match '^\s*\{') {
        $FunctionDepth = [Math]::Max(
          0,
          (Get-BashStructuralBraceDelta $Visible))
        $PendingFunction = $false
        continue
      }
      $PendingFunction = $false
    }

    $FunctionWithBody = [regex]::Match(
      $Visible,
      '^\s*(?:(?:function\s+)(?<function_name>[A-Za-z_][A-Za-z0-9_-]*)(?:\s*\(\s*\))?|(?<posix_name>[A-Za-z_][A-Za-z0-9_-]*)\s*\(\s*\))\s*\{(?:\s|$)')
    if ($FunctionWithBody.Success) {
      $FunctionName = if ($FunctionWithBody.Groups['function_name'].Success) {
        $FunctionWithBody.Groups['function_name'].Value
      } else {
        $FunctionWithBody.Groups['posix_name'].Value
      }
      $Commands.Add([pscustomobject]@{
        Kind = 'FunctionDefinition'
        Name = $FunctionName
        Text = $Visible.Trim()
        StartOffset = $LineMatch.Index
        OperatorBefore = $null
        OperatorAfter = $null
        ControlDepth = $ControlDepth
      })
      $FunctionDepth = [Math]::Max(
        0,
        (Get-BashStructuralBraceDelta $Visible))
      continue
    }
    $FunctionSignature = [regex]::Match(
      $Visible,
      '^\s*(?:(?:function\s+)(?<function_name>[A-Za-z_][A-Za-z0-9_-]*)(?:\s*\(\s*\))?|(?<posix_name>[A-Za-z_][A-Za-z0-9_-]*)\s*\(\s*\))\s*$')
    if ($FunctionSignature.Success) {
      $FunctionName = if ($FunctionSignature.Groups['function_name'].Success) {
        $FunctionSignature.Groups['function_name'].Value
      } else {
        $FunctionSignature.Groups['posix_name'].Value
      }
      $Commands.Add([pscustomobject]@{
        Kind = 'FunctionDefinition'
        Name = $FunctionName
        Text = $Visible.Trim()
        StartOffset = $LineMatch.Index
        OperatorBefore = $null
        OperatorAfter = $null
        ControlDepth = $ControlDepth
      })
      $PendingFunction = $true
      continue
    }
    if ($StartedInQuote -or [string]::IsNullOrWhiteSpace($Visible)) {
      continue
    }

    $Trimmed = $Visible.TrimEnd()
    $TrailingBackslashes = 0
    for ($Index = $Trimmed.Length - 1;
        $Index -ge 0 -and $Trimmed[$Index] -eq '\';
        $Index--) {
      $TrailingBackslashes++
    }
    $BackslashContinues = $null -eq $Quote -and
      $TrailingBackslashes % 2 -eq 1
    $LineSegments = @(Split-BashCommandSegments $Trimmed 0)
    $OperatorContinues = $null -eq $Quote -and
      $LineSegments.Count -gt 0 -and
      $LineSegments[-1].OperatorAfter -in @('&&', '||', '|', '|&')
    $Continues = $BackslashContinues -or $OperatorContinues
    if ($CommandParts.Count -eq 0) {
      $CommandStart = $LineMatch.Index
    }
    if ($Continues) {
      if ($BackslashContinues) {
        $CommandParts.Add(
          $Trimmed.Substring(0, $Trimmed.Length - 1).TrimEnd())
      } else {
        $CommandParts.Add($Trimmed)
      }
      continue
    }

    $CommandParts.Add($Trimmed)
    $LogicalCommand = ($CommandParts -join ' ').Trim()
    foreach ($Segment in @(Split-BashCommandSegments `
        $LogicalCommand `
        $CommandStart)) {
      $SegmentText = $Segment.Text.Trim()
      if ($SegmentText -match '^\s*(?:fi|done|esac)(?:\s|$)' -or
          $SegmentText -match '^\s*[})](?:\s|$|[<>])') {
        $ControlDepth = [Math]::Max(0, $ControlDepth - 1)
        continue
      }

      $OpensControl = Test-BashControlOpener $SegmentText
      $SegmentDepth = $ControlDepth
      if ($OpensControl) {
        $SegmentDepth++
      }
      $Commands.Add([pscustomobject]@{
        Kind = 'Command'
        Name = $null
        Text = $Segment.Text
        StartOffset = $Segment.StartOffset
        OperatorBefore = $Segment.OperatorBefore
        OperatorAfter = $Segment.OperatorAfter
        ControlDepth = $SegmentDepth
      })
      if ($OpensControl) {
        $ControlDepth++
      }
    }
    $CommandParts.Clear()
    $CommandStart = -1
  }

  return @($Commands)
}

function Assert-Count([string] $Label, [int] $Actual, [int] $Expected) {
  if ($Actual -ne $Expected) {
    Add-GateFailure "$Label expected=$Expected actual=$Actual"
  }
}

function Get-RepoRelativePath([string] $Path) {
  $FullPath = [System.IO.Path]::GetFullPath($Path)
  $Prefix = $Root.TrimEnd("\") + "\"
  if ($FullPath.StartsWith($Prefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    return $FullPath.Substring($Prefix.Length)
  }
  return $FullPath
}

function Get-ProjectVersion {
  $BuildFile = Join-Path $Root "build.gradle.kts"
  $BuildText = [System.IO.File]::ReadAllText($BuildFile)
  $Match = [regex]::Match($BuildText, '(?m)^\s*version\s*=\s*"(?<version>[^"]+)"\s*$')
  if (-not $Match.Success) {
    Add-GateFailure "Unable to read project.version from build.gradle.kts"
    return $null
  }
  return $Match.Groups["version"].Value
}

function Test-Metadata {
  $DescriptorPath = Join-Path $Root "starx-plugins\starx-velocity\src\main\resources\velocity-plugin.json"
  $VelocityBuildPath = Join-Path $Root "starx-plugins\starx-velocity\build.gradle.kts"

  if (-not (Test-Path -LiteralPath $DescriptorPath -PathType Leaf)) {
    Add-GateFailure "Missing source plugin descriptor: $(Get-RepoRelativePath $DescriptorPath)"
    return
  }
  if (-not (Test-Path -LiteralPath $VelocityBuildPath -PathType Leaf)) {
    Add-GateFailure "Missing Velocity Gradle build file: $(Get-RepoRelativePath $VelocityBuildPath)"
    return
  }

  try {
    $Descriptor = [System.IO.File]::ReadAllText($DescriptorPath) | ConvertFrom-Json
  } catch {
    Add-GateFailure "Source velocity-plugin.json is invalid JSON: $($_.Exception.Message)"
    return
  }

  if ($Descriptor.id -ne "starx") {
    Add-GateFailure "Source plugin id must remain starx, actual=$($Descriptor.id)"
  }
  if ($Descriptor.version -ne '${version}') {
    Add-GateFailure 'Source plugin version must be the exact ${version} token'
  }
  if ($Descriptor.main -ne "io.github.addxiaoyi.starx.velocity.StarxVelocityPlugin") {
    Add-GateFailure "Unexpected plugin main class: $($Descriptor.main)"
  }
  if ([string]$Descriptor.description -notmatch '(?i)\bUworld\b') {
    Add-GateFailure "Plugin description must name the embedded Uworld runtime"
  }

  $VelocityBuild = [System.IO.File]::ReadAllText($VelocityBuildPath)
  if ($VelocityBuild -notmatch 'filesMatching\s*\(\s*"velocity-plugin\.json"\s*\)') {
    Add-GateFailure "processResources must target velocity-plugin.json"
  }
  if ($VelocityBuild -notmatch 'expand\s*\(\s*"version"\s+to\s+project\.version\s*\)') {
    Add-GateFailure "processResources must expand version from project.version"
  }
  if ($VelocityBuild -notmatch 'systemProperty\s*\(\s*"starx\.project\.version"\s*,\s*project\.version\.toString\(\)\s*\)') {
    Add-GateFailure "Velocity tests must receive starx.project.version from Gradle"
  }
  if ($VelocityBuild -notmatch 'archiveFileName\.set\s*\(\s*"starx-velocity\.jar"\s*\)') {
    Add-GateFailure "Shadow JAR must be named starx-velocity.jar"
  }
  if ($VelocityBuild -notmatch 'tasks\.jar\s*\{[\s\S]{0,160}?enabled\s*=\s*false') {
    Add-GateFailure "Thin JAR task must be disabled"
  }
}

function Invoke-CheckedProcess([string] $FilePath, [string[]] $ArgumentList, [string] $Label) {
  & $FilePath @ArgumentList
  $ExitCode = $LASTEXITCODE
  if ($ExitCode -ne 0) {
    throw "$Label failed with exit code $ExitCode"
  }
}

function Invoke-FreshBuild {
  $Runner = Join-Path $Root "scripts\invoke-gradle-ascii.ps1"
  $SyncTest = Join-Path $Root "scripts\tests\sync-starx-limbo.Tests.ps1"
  $VelocityPinTest = Join-Path $Root "scripts\tests\velocity-build606.Tests.ps1"
  if (-not (Test-Path -LiteralPath $Runner -PathType Leaf)) {
    throw "Missing Gradle runner: $Runner"
  }
  if (-not (Test-Path -LiteralPath $SyncTest -PathType Leaf)) {
    throw "Missing Limbo source synchronization test: $SyncTest"
  }
  if (-not (Test-Path -LiteralPath $VelocityPinTest -PathType Leaf)) {
    throw "Missing Velocity build 606 pin test: $VelocityPinTest"
  }

  $PowerShell = (Get-Process -Id $PID).Path
  Invoke-CheckedProcess $PowerShell @(
    "-NoProfile",
    "-ExecutionPolicy", "Bypass",
    "-File", $VelocityPinTest
  ) "Velocity build 606 pin test"
  Invoke-CheckedProcess $PowerShell @(
    "-NoProfile",
    "-ExecutionPolicy", "Bypass",
    "-File", $SyncTest
  ) "Limbo source synchronization test"

  $GradleArgs = @(
    ":verifyVelocityBuild606",
    ":starx-plugins:starx-limbo-api:clean",
    ":starx-plugins:starx-common:clean",
    ":starx-plugins:starx-standalone-limbo:clean",
    ":starx-plugins:starx-velocity:clean",
    ":starx-plugins:starx-limbo-api:test",
    ":starx-plugins:starx-common:test",
    ":starx-plugins:starx-standalone-limbo:test",
    ":starx-plugins:starx-velocity:test",
    ":starx-plugins:starx-velocity:compileJava",
    ":starx-plugins:starx-velocity:build",
    ":starx-plugins:starx-velocity:shadowJar",
    "--rerun-tasks",
    "--no-parallel",
    "--no-daemon",
    "--console=plain"
  )
  Invoke-CheckedProcess $PowerShell (@(
    "-NoProfile",
    "-ExecutionPolicy", "Bypass",
    "-File", $Runner
  ) + $GradleArgs) "Fresh Uworld Gradle build"
}

function Get-XmlCount([System.Xml.XmlElement] $Suite, [string] $Name) {
  $Value = $Suite.GetAttribute($Name)
  if ([string]::IsNullOrWhiteSpace($Value)) {
    return 0
  }
  return [int]$Value
}

function Test-JunitResults {
  $Projects = @(
    @{ Name = "starx-limbo-api"; Path = "starx-plugins\starx-limbo-api\build\test-results\test" },
    @{ Name = "starx-common"; Path = "starx-plugins\starx-common\build\test-results\test" },
    @{ Name = "starx-standalone-limbo"; Path = "starx-plugins\starx-standalone-limbo\build\test-results\test" },
    @{ Name = "starx-velocity"; Path = "starx-plugins\starx-velocity\build\test-results\test" }
  )

  $TotalSuites = 0
  $TotalTests = 0
  $TotalFailures = 0
  $TotalErrors = 0
  $TotalSkipped = 0

  foreach ($Project in $Projects) {
    $Directory = Join-Path $Root $Project.Path
    $Files = @(Get-ChildItem -LiteralPath $Directory -Filter "TEST-*.xml" -File -ErrorAction SilentlyContinue)
    $SuiteCount = 0
    $TestCount = 0
    $FailureCount = 0
    $ErrorCount = 0
    $SkippedCount = 0

    foreach ($File in $Files) {
      try {
        [xml] $Document = [System.IO.File]::ReadAllText($File.FullName)
        if ($Document.DocumentElement.LocalName -eq "testsuite") {
          $Suites = @($Document.DocumentElement)
        } elseif ($Document.DocumentElement.LocalName -eq "testsuites") {
          $Suites = @($Document.DocumentElement.SelectNodes("./testsuite"))
        } else {
          Add-GateFailure "Unexpected JUnit XML root in $(Get-RepoRelativePath $File.FullName): $($Document.DocumentElement.LocalName)"
          continue
        }
      } catch {
        Add-GateFailure "Invalid JUnit XML $(Get-RepoRelativePath $File.FullName): $($_.Exception.Message)"
        continue
      }

      foreach ($Suite in $Suites) {
        $SuiteCount++
        $TestCount += Get-XmlCount $Suite "tests"
        $FailureCount += Get-XmlCount $Suite "failures"
        $ErrorCount += Get-XmlCount $Suite "errors"
        $SkippedCount += Get-XmlCount $Suite "skipped"
      }
    }

    if ($SuiteCount -eq 0) {
      Add-GateFailure "No JUnit suites found for $($Project.Name) in $($Project.Path)"
    }
    if ($FailureCount -ne 0 -or $ErrorCount -ne 0) {
      Add-GateFailure "JUnit failures for $($Project.Name): failures=$FailureCount errors=$ErrorCount"
    }

    Write-Host "TEST_RESULTS project=$($Project.Name) suites=$SuiteCount tests=$TestCount failures=$FailureCount errors=$ErrorCount skipped=$SkippedCount"
    $TotalSuites += $SuiteCount
    $TotalTests += $TestCount
    $TotalFailures += $FailureCount
    $TotalErrors += $ErrorCount
    $TotalSkipped += $SkippedCount
  }

  Write-Host "TEST_RESULTS total suites=$TotalSuites tests=$TotalTests failures=$TotalFailures errors=$TotalErrors skipped=$TotalSkipped"
}

function Read-ZipEntry([System.IO.Compression.ZipArchiveEntry] $Entry) {
  $Stream = $Entry.Open()
  $Reader = [System.IO.StreamReader]::new($Stream)
  try {
    return $Reader.ReadToEnd()
  } finally {
    $Reader.Dispose()
    $Stream.Dispose()
  }
}

function Test-Jar {
  if (-not (Test-Path -LiteralPath $JarPath -PathType Leaf)) {
    Add-GateFailure "Missing final plugin artifact: $(Get-RepoRelativePath $JarPath)"
    return $null
  }
  if ([System.IO.Path]::GetFileName($JarPath) -cne "starx-velocity.jar") {
    Add-GateFailure "Deployable artifact must be named starx-velocity.jar, actual=$([System.IO.Path]::GetFileName($JarPath))"
  }

  $ArtifactDirectory = [System.IO.Path]::GetDirectoryName($JarPath)
  $DeploymentCopies = @(Get-ChildItem -LiteralPath $ArtifactDirectory -File -Filter "starx-velocity.jar")
  Assert-Count "Deployable starx-velocity.jar files" $DeploymentCopies.Count 1
  $UnexpectedArtifacts = @(
    Get-ChildItem -LiteralPath $ArtifactDirectory -File -Filter "starx-velocity*.jar" |
      Where-Object { $_.Name -cne "starx-velocity.jar" }
  )
  if ($UnexpectedArtifacts.Count -ne 0) {
    $UnexpectedNames = @($UnexpectedArtifacts | ForEach-Object { $_.Name }) -join ", "
    Add-GateFailure "Unexpected starx-velocity JAR artifacts: $UnexpectedNames"
  }

  Add-Type -AssemblyName System.IO.Compression.FileSystem
  try {
    $Zip = [System.IO.Compression.ZipFile]::OpenRead($JarPath)
  } catch {
    Add-GateFailure "Unable to open plugin artifact as a JAR: $($_.Exception.Message)"
    return $null
  }
  try {
    $Entries = @($Zip.Entries)
    $Names = @($Entries | ForEach-Object { $_.FullName })

    $RequiredEntries = @(
      "io/github/addxiaoyi/starx/uworld/UworldRuntime.class",
      "io/github/addxiaoyi/starx/uworld/UworldSpec.class",
      "io/github/addxiaoyi/starx/uworld/StarxUworldFactory.class",
      "io/github/addxiaoyi/starx/limbo/StarxLimboFactory.class",
      "io/github/addxiaoyi/starx/velocity/config/UworldConfig.class",
      "io/github/addxiaoyi/starx/velocity/module/uworld/UworldModule.class",
      "io/github/addxiaoyi/starx/velocity/module/uworld/EmbeddedUworldRuntime.class",
      "io/github/addxiaoyi/starx/velocity/module/uworld/UworldDiagnostics.class",
      "io/github/addxiaoyi/starx/velocity/module/proxytools/HubCommandModule.class",
      "default-config.yml",
      "velocity-plugin.json"
    )
    foreach ($RequiredEntry in $RequiredEntries) {
      Assert-Count "JAR entry $RequiredEntry" @($Names | Where-Object { $_ -ceq $RequiredEntry }).Count 1
    }

    $OldRuntimeEntries = @(
      "io/github/addxiaoyi/starx/velocity/module/limbo/LimboModule",
      "io/github/addxiaoyi/starx/velocity/module/limbo/LimboTransportSession",
      "io/github/addxiaoyi/starx/velocity/module/limbo/LimboTransferState",
      "io/github/addxiaoyi/starx/velocity/module/proxytools/LimboHubModule"
    )
    foreach ($OldEntry in $OldRuntimeEntries) {
      $OldEntryPattern = '^' + [regex]::Escape($OldEntry) + '(?:\$[^/]*)?\.class$'
      Assert-Count "Forbidden legacy runtime class $OldEntry" @($Names | Where-Object { $_ -match $OldEntryPattern }).Count 0
    }

    $MappingFiles = @($Names | Where-Object { $_ -match '^mapping/[^/]+$' }).Count
    $FastPrepareEntries = @($Names | Where-Object { $_ -match '^io/github/addxiaoyi/starx/limbo/thirdparty/fastprepare/' }).Count
    $CommonsEntries = @($Names | Where-Object { $_ -match '^io/github/addxiaoyi/starx/limbo/thirdparty/commons/' }).Count
    $ExternalLimboClasses = @($Names | Where-Object { $_ -match '^net/elytrium/limboapi/.+\.class$' }).Count
    $NestedJars = @($Names | Where-Object { $_ -match '(?i)\.jar$' }).Count
    $Descriptors = @($Entries | Where-Object { $_.FullName -ceq "velocity-plugin.json" })

    Assert-Count "Mapping files" $MappingFiles 26
    Assert-Count "Relocated FastPrepare entries" $FastPrepareEntries 10
    Assert-Count "Relocated Elytrium Commons entries" $CommonsEntries 31
    Assert-Count "External net/elytrium/limboapi classes" $ExternalLimboClasses 0
    Assert-Count "Nested JARs" $NestedJars 0
    Assert-Count "Velocity plugin descriptors" $Descriptors.Count 1

    if ($Descriptors.Count -eq 1) {
      try {
        $Descriptor = Read-ZipEntry $Descriptors[0] | ConvertFrom-Json
        $ExpectedVersion = Get-ProjectVersion
        if ($Descriptor.id -ne "starx") {
          Add-GateFailure "Packaged plugin id must remain starx, actual=$($Descriptor.id)"
        }
        if ($null -ne $ExpectedVersion -and $Descriptor.version -ne $ExpectedVersion) {
          Add-GateFailure "Packaged plugin version must equal project.version expected=$ExpectedVersion actual=$($Descriptor.version)"
        }
        if ([string]$Descriptor.description -notmatch '(?i)\bUworld\b') {
          Add-GateFailure "Packaged plugin description must name Uworld"
        }
        if ($Descriptor.main -ne "io.github.addxiaoyi.starx.velocity.StarxVelocityPlugin") {
          Add-GateFailure "Unexpected packaged plugin main class: $($Descriptor.main)"
        }
        foreach ($Dependency in @($Descriptor.dependencies)) {
          if ($null -ne $Dependency -and [string]$Dependency.id -match '(?i)limbo') {
            Add-GateFailure "Packaged plugin descriptor must not depend on an external Limbo plugin: $($Dependency.id)"
          }
        }
      } catch {
        Add-GateFailure "Packaged velocity-plugin.json is invalid: $($_.Exception.Message)"
      }
    }

    $DefaultConfigEntry = @($Entries | Where-Object { $_.FullName -ceq "default-config.yml" })
    if ($DefaultConfigEntry.Count -eq 1) {
      $DefaultConfig = Read-ZipEntry $DefaultConfigEntry[0]
      if ($DefaultConfig -notmatch '(?m)^uworld:\s*$') {
        Add-GateFailure "Packaged default-config.yml is missing the uworld root"
      }
      if ($DefaultConfig -notmatch '(?m)^\s+starx\.uworld:\s*$') {
        Add-GateFailure "Packaged default-config.yml is missing modules.starx.uworld"
      }
    }

    Write-Host "JAR_COUNTS mappings=$MappingFiles fastprepare=$FastPrepareEntries commons=$CommonsEntries external_limbo_classes=$ExternalLimboClasses nested_jars=$NestedJars descriptors=$($Descriptors.Count)"
  } finally {
    $Zip.Dispose()
  }

  return Get-Item -LiteralPath $JarPath
}

function Get-ProductionJavaFiles {
  return @(Get-ChildItem -LiteralPath (Join-Path $Root "starx-plugins") -Recurse -File -Filter "*.java" |
    Where-Object { $_.FullName -match '[\\/]src[\\/]main[\\/]java[\\/]' })
}

function Add-LineFailure([string] $Label, [System.IO.FileInfo] $File, [int] $LineNumber, [string] $Line) {
  Add-GateFailure "$Label $(Get-RepoRelativePath $File.FullName):$LineNumber $($Line.Trim())"
}

function Test-SourceRules {
  $ProductionJava = Get-ProductionJavaFiles
  $LegacyLiteral = '"starx.limbo"'
  $LegacyAllowlist = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
  @(
    "starx-plugins\starx-velocity\src\main\java\io\github\addxiaoyi\starx\velocity\config\ConfigLoader.java",
    "starx-plugins\starx-velocity\src\main\java\io\github\addxiaoyi\starx\velocity\config\StarxConfig.java",
    "starx-plugins\starx-velocity\src\main\java\io\github\addxiaoyi\starx\velocity\config\UworldConfig.java",
    "starx-plugins\starx-velocity\src\main\java\io\github\addxiaoyi\starx\velocity\module\ModuleManager.java"
  ) | ForEach-Object { $null = $LegacyAllowlist.Add($_) }

  $OldRuntimeNames = @("Limbo" + "Module", "Limbo" + "TransportSession", "Limbo" + "TransferState", "Limbo" + "HubModule")
  $ServerEnumerationAllowlist = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
  @(
    "starx-plugins\starx-velocity\src\main\java\io\github\addxiaoyi\starx\velocity\module\proxytools\ProxyInfoModule.java",
    "starx-plugins\starx-velocity\src\main\java\io\github\addxiaoyi\starx\velocity\module\proxytools\EnhancedProxyModule.java",
    "starx-plugins\starx-velocity\src\main\java\io\github\addxiaoyi\starx\velocity\module\integrations\PlanIntegrationModule.java",
    "starx-plugins\starx-velocity\src\main\java\io\github\addxiaoyi\starx\velocity\http\NetworkStatusHandler.java",
    "starx-plugins\starx-velocity\src\main\java\io\github\addxiaoyi\starx\velocity\bridge\VelocityBackendBridge.java"
  ) | ForEach-Object { $null = $ServerEnumerationAllowlist.Add($_) }
  $FallbackPatterns = @(
    'getAllServers\s*\(\s*\)\s*\.\s*stream\s*\(\s*\)[^;]{0,500}?\.\s*find(?:First|Any)\s*\(',
    'getAllServers\s*\(\s*\)\s*\.\s*iterator\s*\(\s*\)\s*\.\s*next\s*\('
  )

  foreach ($File in $ProductionJava) {
    $Relative = Get-RepoRelativePath $File.FullName
    $Lines = [System.IO.File]::ReadAllLines($File.FullName)
    for ($Index = 0; $Index -lt $Lines.Length; $Index++) {
      $Line = $Lines[$Index]
      if ($Line.Contains($LegacyLiteral) -and -not $LegacyAllowlist.Contains($Relative)) {
        Add-LineFailure "Legacy module id outside migration allowlist" $File ($Index + 1) $Line
      }
      if ($Line -match 'getAllServers\s*\(' -and -not $ServerEnumerationAllowlist.Contains($Relative)) {
        Add-LineFailure "Server enumeration outside reporting allowlist; resolve an exact target with getServer" $File ($Index + 1) $Line
      }
      foreach ($OldRuntimeName in $OldRuntimeNames) {
        if ($Line.Contains($OldRuntimeName)) {
          Add-LineFailure "Forbidden legacy runtime type $OldRuntimeName" $File ($Index + 1) $Line
        }
      }
    }

    $Text = [System.IO.File]::ReadAllText($File.FullName)
    foreach ($Pattern in $FallbackPatterns) {
      foreach ($Match in [regex]::Matches($Text, $Pattern, [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)) {
        $LineNumber = ([regex]::Matches($Text.Substring(0, $Match.Index), "`n")).Count + 1
        $Snippet = [regex]::Replace($Match.Value, '\s+', ' ')
        Add-GateFailure "Arbitrary first-server fallback $(Get-RepoRelativePath $File.FullName):$LineNumber $Snippet"
      }
    }
  }

  $CompatibilitySource = "starx-plugins\starx-standalone-limbo\src\main\java\io\github\addxiaoyi\starx\limbo\StarxLimboFactory.java"
  foreach ($File in $ProductionJava) {
    $Relative = Get-RepoRelativePath $File.FullName
    if ($Relative -ieq $CompatibilitySource) {
      continue
    }
    $Lines = [System.IO.File]::ReadAllLines($File.FullName)
    for ($Index = 0; $Index -lt $Lines.Length; $Index++) {
      if ($Lines[$Index].Contains("Starx" + "LimboFactory")) {
        Add-LineFailure "StarxLimboFactory outside compatibility source" $File ($Index + 1) $Lines[$Index]
      }
    }
  }

  $CompatibilityPath = Join-Path $Root $CompatibilitySource
  if (-not (Test-Path -LiteralPath $CompatibilityPath -PathType Leaf)) {
    Add-GateFailure "Missing one-major-version StarxLimboFactory compatibility source"
  } else {
    $CompatibilityText = [System.IO.File]::ReadAllText($CompatibilityPath)
    if ($CompatibilityText -notmatch '@Deprecated\s*\(\s*forRemoval\s*=\s*true\s*\)') {
      Add-GateFailure "StarxLimboFactory compatibility entry must be marked for removal"
    }
    if ($CompatibilityText -notmatch 'class\s+StarxLimboFactory\s+extends\s+StarxUworldFactory') {
      Add-GateFailure "StarxLimboFactory must forward through StarxUworldFactory"
    }
  }

  $ConfigLoaderPath = Join-Path $Root "starx-plugins\starx-velocity\src\main\java\io\github\addxiaoyi\starx\velocity\config\ConfigLoader.java"
  if (-not (Test-Path -LiteralPath $ConfigLoaderPath -PathType Leaf)) {
    Add-GateFailure "Missing ConfigLoader legacy Uworld migration boundary"
  } else {
    $ConfigLoaderText = [System.IO.File]::ReadAllText($ConfigLoaderPath)
    if ($ConfigLoaderText -notmatch 'containsKey\s*\(\s*"limbo"\s*\)') {
      Add-GateFailure "ConfigLoader must retain the legacy limbo root for one-major-version migration"
    }
    if ($ConfigLoaderText -notmatch 'containsKey\s*\(\s*"starx\.limbo"\s*\)') {
      Add-GateFailure "ConfigLoader must retain the legacy starx.limbo module key"
    }
  }

  $StarxConfigPath = Join-Path $Root "starx-plugins\starx-velocity\src\main\java\io\github\addxiaoyi\starx\velocity\config\StarxConfig.java"
  if (-not (Test-Path -LiteralPath $StarxConfigPath -PathType Leaf)) {
    Add-GateFailure "Missing StarxConfig legacy module fallback"
  } else {
    $StarxConfigText = [System.IO.File]::ReadAllText($StarxConfigPath)
    if ($StarxConfigText -notmatch 'modules\.get\s*\(\s*"starx\.limbo"\s*\)') {
      Add-GateFailure "StarxConfig must retain the legacy starx.limbo module fallback"
    }
  }

  $ModuleManagerPath = Join-Path $Root "starx-plugins\starx-velocity\src\main\java\io\github\addxiaoyi\starx\velocity\module\ModuleManager.java"
  if (-not (Test-Path -LiteralPath $ModuleManagerPath -PathType Leaf)) {
    Add-GateFailure "Missing ModuleManager Uworld compatibility alias"
  } else {
    $ModuleManagerText = [System.IO.File]::ReadAllText($ModuleManagerPath)
    if ($ModuleManagerText -notmatch '"starx\.limbo"[\s\S]{0,160}"starx\.uworld"') {
      Add-GateFailure "ModuleManager must resolve starx.limbo queries to starx.uworld"
    }
  }

  $ScopedJava = [System.Collections.Generic.List[System.IO.FileInfo]]::new()
  @(
    "starx-plugins\starx-limbo-api\src\main\java\io\github\addxiaoyi\starx\uworld",
    "starx-plugins\starx-velocity\src\main\java\io\github\addxiaoyi\starx\velocity\module\uworld"
  ) | ForEach-Object {
    $Directory = Join-Path $Root $_
    if (Test-Path -LiteralPath $Directory -PathType Container) {
      Get-ChildItem -LiteralPath $Directory -Recurse -File -Filter "*.java" | ForEach-Object { $ScopedJava.Add($_) }
    }
  }
  @(
    "starx-plugins\starx-standalone-limbo\src\main\java\io\github\addxiaoyi\starx\uworld\StarxUworldFactory.java",
    "starx-plugins\starx-standalone-limbo\src\main\java\io\github\addxiaoyi\starx\limbo\StarxLimboFactory.java",
    "starx-plugins\starx-standalone-limbo\src\main\java\io\github\addxiaoyi\starx\limbo\LimboAPI.java",
    "starx-plugins\starx-standalone-limbo\src\main\java\io\github\addxiaoyi\starx\limbo\LimboPlayerState.java",
    "starx-plugins\starx-velocity\src\main\java\io\github\addxiaoyi\starx\velocity\StarxVelocityPlugin.java",
    "starx-plugins\starx-velocity\src\main\java\io\github\addxiaoyi\starx\velocity\module\ModuleManager.java",
    "starx-plugins\starx-velocity\src\main\java\io\github\addxiaoyi\starx\velocity\module\auth\AuthModule.java",
    "starx-plugins\starx-velocity\src\main\java\io\github\addxiaoyi\starx\velocity\module\auth\AuthFlowIndex.java",
    "starx-plugins\starx-velocity\src\main\java\io\github\addxiaoyi\starx\velocity\config\ConfigLoader.java",
    "starx-plugins\starx-velocity\src\main\java\io\github\addxiaoyi\starx\velocity\config\StarxConfig.java",
    "starx-plugins\starx-velocity\src\main\java\io\github\addxiaoyi\starx\velocity\config\UworldConfig.java",
    "starx-plugins\starx-common\src\main\java\io\github\addxiaoyi\starx\common\auth\AuthActions.java",
    "starx-plugins\starx-common\src\main\java\io\github\addxiaoyi\starx\common\auth\AuthCommandHandler.java",
    "starx-plugins\starx-common\src\main\java\io\github\addxiaoyi\starx\common\auth\AuthService.java",
    "starx-plugins\starx-common\src\main\java\io\github\addxiaoyi\starx\common\auth\SessionManager.java"
  ) | ForEach-Object {
    $Path = Join-Path $Root $_
    if (Test-Path -LiteralPath $Path -PathType Leaf) {
      $ScopedJava.Add((Get-Item -LiteralPath $Path))
    }
  }

  $DeliveredDocs = @(
    "starx-plugins\README.md",
    "starx-plugins\starx-velocity\README.md",
    "starx-plugins\starx-standalone-limbo\README.md",
    "docs\UWORLD_CONFIGURATION.md",
    "docs\UWORLD_DEVELOPMENT.md",
    "docs\UWORLD_ENVIRONMENT.md",
    "docs\UWORLD_ACCEPTANCE.md"
  )
  $Markers = @(
    "TO" + "DO",
    "FIX" + "ME",
    "T" + "BD",
    "Unsupported" + "OperationException",
    "not " + "implemented"
  )
  $MarkerPattern = ($Markers | ForEach-Object { [regex]::Escape($_) }) -join '|'
  $MarkerFiles = @($ScopedJava | Sort-Object FullName -Unique)
  foreach ($RelativeDoc in $DeliveredDocs) {
    $DocPath = Join-Path $Root $RelativeDoc
    if (Test-Path -LiteralPath $DocPath -PathType Leaf) {
      $MarkerFiles += Get-Item -LiteralPath $DocPath
    }
  }
  foreach ($File in $MarkerFiles) {
    $Lines = [System.IO.File]::ReadAllLines($File.FullName)
    for ($Index = 0; $Index -lt $Lines.Length; $Index++) {
      if ($Lines[$Index] -match $MarkerPattern) {
        Add-LineFailure "Unfinished implementation marker" $File ($Index + 1) $Lines[$Index]
      }
    }
  }
}

function Test-Documentation {
  $Documents = @(
    "starx-plugins\README.md",
    "starx-plugins\starx-velocity\README.md",
    "starx-plugins\starx-standalone-limbo\README.md",
    "starx-plugins\starx-standalone-limbo\UPSTREAM.md",
    "docs\UWORLD_CONFIGURATION.md",
    "docs\UWORLD_DEVELOPMENT.md",
    "docs\UWORLD_ENVIRONMENT.md",
    "docs\UWORLD_ACCEPTANCE.md"
  )

  @(
    "scripts\check-uworld-environment.ps1",
    "scripts\tests\check-uworld-environment.Tests.ps1"
  ) | ForEach-Object {
    $RequiredPath = Join-Path $Root $_
    if (-not (Test-Path -LiteralPath $RequiredPath -PathType Leaf)) {
      Add-GateFailure "Missing Uworld environment command: $_"
    }
  }

  foreach ($RelativeDocument in $Documents) {
    $DocumentPath = Join-Path $Root $RelativeDocument
    if (-not (Test-Path -LiteralPath $DocumentPath -PathType Leaf)) {
      Add-GateFailure "Missing Uworld documentation: $RelativeDocument"
      continue
    }

    $Lines = [System.IO.File]::ReadAllLines($DocumentPath)
    for ($Index = 0; $Index -lt $Lines.Length; $Index++) {
      foreach ($Match in [regex]::Matches($Lines[$Index], '!?\[[^\]]*\]\((?<target>[^)]+)\)')) {
        $Target = $Match.Groups["target"].Value.Trim()
        if ($Target.StartsWith("<") -and $Target.EndsWith(">")) {
          $Target = $Target.Substring(1, $Target.Length - 2)
        } elseif ($Target -match '^\S+') {
          $Target = $Matches[0]
        }
        if ([string]::IsNullOrWhiteSpace($Target) -or $Target.StartsWith("#") -or $Target -match '^[A-Za-z][A-Za-z0-9+.-]*:') {
          continue
        }
        $Target = ($Target -split '[?#]', 2)[0]
        $Target = [System.Uri]::UnescapeDataString($Target)
        $ResolvedTarget = [System.IO.Path]::GetFullPath((Join-Path ([System.IO.Path]::GetDirectoryName($DocumentPath)) $Target))
        if (-not (Test-Path -LiteralPath $ResolvedTarget)) {
          Add-LineFailure "Missing local Markdown link target '$Target'" (Get-Item -LiteralPath $DocumentPath) ($Index + 1) $Lines[$Index]
        }
      }
    }
  }

  $StandaloneReadme = Join-Path $Root "starx-plugins\starx-standalone-limbo\README.md"
  if (Test-Path -LiteralPath $StandaloneReadme -PathType Leaf) {
    $Text = [System.IO.File]::ReadAllText($StandaloneReadme)
    $NotMeans = -join @([char]0x4E0D, [char]0x8868, [char]0x793A)
    $NotIs = -join @([char]0x4E0D, [char]0x662F)
    $MustNot = -join @([char]0x7981, [char]0x6B62)
    $DoNot = -join @([char]0x4E0D, [char]0x8981)
    $Cannot = -join @([char]0x4E0D, [char]0x80FD)
    $OnlyDeploy = -join @([char]0x53EA, [char]0x90E8, [char]0x7F72)
    $Deploy = -join @([char]0x90E8, [char]0x7F72)
    $NegativePattern = @($NotMeans, $NotIs, $MustNot, $DoNot, $Cannot) |
      ForEach-Object { [regex]::Escape($_) }
    $NegativePattern = ($NegativePattern + @("do not", "must not", "cannot", "not a")) -join '|'
    if ($Text -notmatch "(?is)($NegativePattern).{0,80}Velocity") {
      Add-GateFailure "Standalone README must state that the library is not a Velocity plugin"
    }
    $OnlyDeploymentPattern = "(?is)(" +
      [regex]::Escape($OnlyDeploy) + ".{0,80}starx-velocity\.jar|" +
      "only.{0,40}(production )?deploy(ment|ed|able)?[^\r\n]{0,80}starx-velocity\.jar)"
    if ($Text -notmatch $OnlyDeploymentPattern) {
      Add-GateFailure "Standalone README must state that starx-velocity.jar is the only production deployment"
    }
    $Lines = [System.IO.File]::ReadAllLines($StandaloneReadme)
    for ($Index = 0; $Index -lt $Lines.Length; $Index++) {
      $Line = $Lines[$Index]
      $VerbPattern = "(?i)(copy|place|install|deploy|$([regex]::Escape($Deploy)))"
      $ClaimsInstall = $Line -match "(?i)((starx-standalone-limbo|standalone).{0,120}$VerbPattern.{0,120}Velocity|$VerbPattern.{0,120}(starx-standalone-limbo|standalone).{0,120}Velocity)"
      $IsNegated = $Line -match "(?i)($NegativePattern)"
      if ($ClaimsInstall -and -not $IsNegated) {
        Add-LineFailure "Standalone README claims a separately installable Velocity plugin" (Get-Item -LiteralPath $StandaloneReadme) ($Index + 1) $Line
      }
    }
    $ProtocolMeaning = '(?is)776.{0,240}ProtocolVersion\.MAXIMUM_VERSION.{0,240}(not|\u4e0d\u662f).{0,80}(client|\u5ba2\u6237\u7aef).{0,80}(minimum|\u4e0b\u9650)'
    if ($Text -notmatch $ProtocolMeaning) {
      Add-GateFailure "Standalone README must define protocol 776 as the minimum Velocity MAXIMUM_VERSION, not a client minimum"
    }
  }

  $CompatibilityName = "Starx" + "LimboFactory"
  $AllowedCompatibilityDocs = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
  @(
    "starx-plugins\starx-standalone-limbo\README.md",
    "starx-plugins\starx-standalone-limbo\UPSTREAM.md",
    "docs\UWORLD_CONFIGURATION.md",
    "docs\UWORLD_ACCEPTANCE.md"
  ) | ForEach-Object { $null = $AllowedCompatibilityDocs.Add($_) }
  foreach ($RelativeDocument in $Documents) {
    $DocumentPath = Join-Path $Root $RelativeDocument
    if (-not (Test-Path -LiteralPath $DocumentPath -PathType Leaf) -or $AllowedCompatibilityDocs.Contains($RelativeDocument)) {
      continue
    }
    $Lines = [System.IO.File]::ReadAllLines($DocumentPath)
    for ($Index = 0; $Index -lt $Lines.Length; $Index++) {
      if ($Lines[$Index].Contains($CompatibilityName)) {
        Add-LineFailure "StarxLimboFactory outside migration documentation" (Get-Item -LiteralPath $DocumentPath) ($Index + 1) $Lines[$Index]
      }
    }
  }

  $ProductReadme = Join-Path $Root "starx-plugins\README.md"
  if (Test-Path -LiteralPath $ProductReadme -PathType Leaf) {
    $ProductText = [System.IO.File]::ReadAllText($ProductReadme)
    $Embedded = -join @([char]0x5185, [char]0x7F6E)
    $OnlyArtifact = -join @(
      [char]0x552F, [char]0x4E00, [char]0x90E8, [char]0x7F72, [char]0x7269
    )
    if ($ProductText -notmatch "(?is)Uworld.{0,160}(embedded|$([regex]::Escape($Embedded)))|(embedded|$([regex]::Escape($Embedded))).{0,160}Uworld") {
      Add-GateFailure "Plugin product README must define Uworld as an embedded runtime"
    }
    if ($ProductText -notmatch "(?is)(only|$([regex]::Escape($OnlyArtifact))).{0,80}starx-velocity\.jar|starx-velocity\.jar.{0,80}(only|$([regex]::Escape($OnlyArtifact)))") {
      Add-GateFailure "Plugin product README must name starx-velocity.jar as the only artifact"
    }

    $GoalPatterns = @(
      '(?is)(managed.{0,40}virtual[- ]world|\u53d7\u7ba1.{0,20}\u865a\u62df\u4e16\u754c)',
      '(?is)(player )?session|\u73a9\u5bb6\u4f1a\u8bdd',
      '(?is)timeout|\u8d85\u65f6',
      '(?is)fail-closed.{0,40}cleanup|\u5931\u8d25\u6e05\u7406',
      '(?is)exact[- ]target|\u7cbe\u786e\u76ee\u6807'
    )
    if (@($GoalPatterns | Where-Object { $ProductText -notmatch $_ }).Count -ne 0) {
      Add-GateFailure "Plugin product README must define Uworld product goals"
    }

    $NonGoalPatterns = @(
      '(?is)(not|\u4e0d\u662f).{0,80}(second plugin|\u7b2c\u4e8c\u4e2a\u63d2\u4ef6)',
      '(?is)(external LimboAPI|\u5916\u7f6e LimboAPI)',
      '(?is)(hot reload|\u70ed\u91cd\u8f7d)',
      '(?is)(arbitrary backend fallback|\u4efb\u610f\u540e\u7aef fallback)',
      '(?is)(does not make|\u4e0d\u4ee3\u8868).{0,120}(consumer|\u6d88\u8d39\u8005|\u4e1a\u52a1\u6a21\u5757).{0,120}(real-client|\u771f\u5b9e\u5ba2\u6237\u7aef|\u771f\u5b9e\u73a9\u5bb6)'
    )
    if (@($NonGoalPatterns | Where-Object { $ProductText -notmatch $_ }).Count -ne 0) {
      Add-GateFailure "Plugin product README must define Uworld non-goals"
    }

    $IntegratedRows = @('Auth', 'Diagnostics')
    $IntegratedRowsValid = $true
    foreach ($Consumer in $IntegratedRows) {
      $Pattern = '(?im)^\|\s*' + [regex]::Escape($Consumer) +
        '\s*\|[^\r\n|]*(integrated|\u5df2\u63a5\u5165)[^\r\n|]*\|\s*UNVERIFIED\s*\|\s*$'
      if ($ProductText -notmatch $Pattern) {
        $IntegratedRowsValid = $false
      }
    }
    if (-not $IntegratedRowsValid) {
      Add-GateFailure "Auth and Diagnostics real-client status must remain UNVERIFIED until evidence is recorded"
    }

    $IndependentRowsValid = $true
    foreach ($Consumer in @('Queue', 'Maintenance', 'Tutorial')) {
      $Pattern = '(?im)^\|\s*' + [regex]::Escape($Consumer) +
        '\s*\|[^\r\n|]*(integrated|implemented|\u5df2\u63a5\u5165|\u5df2\u5b9e\u73b0)[^\r\n|]*' +
        '(does not depend on Uworld|independent of Uworld|\u4e0d\u4f9d\u8d56 Uworld)[^\r\n|]*\|\s*IMPLEMENTED\s*\|\s*$'
      if ($ProductText -notmatch $Pattern) {
        $IndependentRowsValid = $false
      }
    }
    if (-not $IndependentRowsValid) {
      Add-GateFailure "Queue, Maintenance, and Tutorial must be documented as implemented independent modules"
    }
  }

  $ConfigurationDocument = Join-Path $Root "docs\UWORLD_CONFIGURATION.md"
  if (Test-Path -LiteralPath $ConfigurationDocument -PathType Leaf) {
    $ConfigurationText = [System.IO.File]::ReadAllText($ConfigurationDocument)
    if ($ConfigurationText -notmatch '(?ms)^\s{2}starx\.auth:\r?\n\s{4}enabled:\s*true\s*$') {
      Add-GateFailure "Uworld configuration must include modules.starx.auth in the default example"
    }
    $SupportedAuth = '(?im)^\|\s*true\s*\|\s*true\s*\|\s*true\s*\|[^\r\n|]*(supported|\u652f\u6301)'
    $StandaloneRuntime = '(?im)^\|\s*false\s*\|\s*true\s*\|\s*true\s*\|[^\r\n|]*(supported|\u652f\u6301)'
    if ($ConfigurationText -notmatch $SupportedAuth -or
        $ConfigurationText -notmatch $StandaloneRuntime) {
      Add-GateFailure "Uworld configuration must document Auth/Uworld enablement combinations"
    }
    $AuthWithoutModule = '(?im)^\|\s*true\s*\|\s*false\s*\|\s*(any|\u4efb\u610f)\s*\|[^\r\n|]*(FAIL_CLOSED|fail closed)'
    $AuthWithoutRuntime = '(?im)^\|\s*true\s*\|\s*true\s*\|\s*false\s*\|[^\r\n|]*(FAIL_CLOSED|fail closed)'
    if ($ConfigurationText -notmatch $AuthWithoutModule -or
        $ConfigurationText -notmatch $AuthWithoutRuntime) {
      Add-GateFailure "Uworld configuration must document Auth without a ready Uworld as fail-closed"
    }
  }

  $DevelopmentDocument = Join-Path $Root "docs\UWORLD_DEVELOPMENT.md"
  if (Test-Path -LiteralPath $DevelopmentDocument -PathType Leaf) {
    $DevelopmentText = [System.IO.File]::ReadAllText($DevelopmentDocument)
    if ($DevelopmentText -notmatch '(?is)(UworldRuntime.{0,300}(business modules|\u4e1a\u52a1\u6a21\u5757)|(business modules|\u4e1a\u52a1\u6a21\u5757).{0,300}UworldRuntime)' -or
        $DevelopmentText -notmatch '(?is)(io\.github\.addxiaoyi\.starx\.limbo.{0,300}(internal|\u5185\u90e8)|(internal|\u5185\u90e8).{0,300}io\.github\.addxiaoyi\.starx\.limbo)') {
      Add-GateFailure "Uworld development guide must define the public and internal API boundary"
    }
    if ($DevelopmentText -notmatch '(?is)StarxUworldFactory.{0,220}(internal|\u5185\u90e8).{0,220}UworldModule|StarxUworldFactory.{0,220}UworldModule.{0,220}(internal|\u5185\u90e8)') {
      Add-GateFailure "StarxUworldFactory must remain an internal UworldModule lifecycle detail"
    }
  }

  $AcceptanceDocument = Join-Path $Root "docs\UWORLD_ACCEPTANCE.md"
  if (Test-Path -LiteralPath $AcceptanceDocument -PathType Leaf) {
    $AcceptanceText = [System.IO.File]::ReadAllText($AcceptanceDocument)
    $CurrentSection = Get-MarkedTextSection $AcceptanceText "UWORLD_CURRENT_CANDIDATE"
    $AutomaticSection = Get-MarkedTextSection $AcceptanceText "UWORLD_AUTOMATIC_EVIDENCE"
    $ColdStartSection = Get-MarkedTextSection $AcceptanceText "UWORLD_COLD_START_EVIDENCE"
    $LiveSection = Get-MarkedTextSection $AcceptanceText "UWORLD_LIVE_ENVIRONMENT_EVIDENCE"
    $RealClientSection = Get-MarkedTextSection $AcceptanceText "UWORLD_REAL_CLIENT_MATRIX"
    if ($null -eq $CurrentSection -or
        $null -eq $AutomaticSection -or
        $null -eq $ColdStartSection) {
      Add-GateFailure "Uworld acceptance must contain marked candidate, automatic, and cold-start evidence sections"
    } else {
      $CurrentValues = Convert-KeyValueSection $CurrentSection
      $AutomaticValues = Convert-KeyValueSection $AutomaticSection
      $ColdStartValues = Convert-KeyValueSection $ColdStartSection
      $CandidateSha = if ($CurrentValues.ContainsKey("sha256")) {
        [string] $CurrentValues["sha256"]
      } else {
        ""
      }
      [long] $CandidateSize = 0
      $CandidateSizeValid = $CurrentValues.ContainsKey("size") -and
        [long]::TryParse([string] $CurrentValues["size"], [ref] $CandidateSize)
      if (-not $CandidateSizeValid) {
        Add-GateFailure "Uworld acceptance candidate size must be an integer"
      }
      if (Test-Path -LiteralPath $JarPath -PathType Leaf) {
        $Artifact = Get-Item -LiteralPath $JarPath
        $ArtifactSha = (Get-FileHash -LiteralPath $Artifact.FullName -Algorithm SHA256).Hash
        if ($CandidateSha -cne $ArtifactSha) {
          Add-GateFailure "Uworld acceptance candidate SHA-256 must match the built artifact"
        }
        if (-not $CandidateSizeValid -or $CandidateSize -ne $Artifact.Length) {
          Add-GateFailure "Uworld acceptance candidate size must match the built artifact"
        }
      } elseif ($CandidateSha -notmatch '^[A-Fa-f0-9]{64}$') {
        Add-GateFailure "Uworld acceptance candidate SHA-256 must be a 64-digit hash"
      }
      $AutomaticSha = if ($AutomaticValues.ContainsKey("ARTIFACT_SHA256")) {
        [string] $AutomaticValues["ARTIFACT_SHA256"]
      } else {
        ""
      }
      $ColdStartSha = if ($ColdStartValues.ContainsKey("artifact_sha256")) {
        [string] $ColdStartValues["artifact_sha256"]
      } else {
        ""
      }
      if ($AutomaticSha -cne $CandidateSha -or $ColdStartSha -cne $CandidateSha) {
        Add-GateFailure "Uworld acceptance evidence must use one candidate SHA-256"
      }

      if ($null -eq $LiveSection) {
        Add-GateFailure "Uworld acceptance must contain marked live environment evidence"
      } else {
        $LiveValues = Convert-KeyValueSection $LiveSection
        $LiveCandidateSha = if ($LiveValues.ContainsKey("candidate_sha256")) {
          [string] $LiveValues["candidate_sha256"]
        } else {
          ""
        }
        $InstalledSha = if ($LiveValues.ContainsKey("installed_sha256")) {
          [string] $LiveValues["installed_sha256"]
        } else {
          ""
        }
        $CandidateHashCheck = if ($LiveValues.ContainsKey("candidate_hash_check")) {
          ([string] $LiveValues["candidate_hash_check"]).ToUpperInvariant()
        } else {
          ""
        }
        if ($LiveCandidateSha -cne $CandidateSha) {
          Add-GateFailure "Uworld acceptance live evidence must use the current candidate SHA-256"
        }
        if ($InstalledSha -notmatch '^[A-Fa-f0-9]{64}$') {
          Add-GateFailure "Uworld acceptance live installed SHA-256 must be a 64-digit hash"
        }
        $HashesMatch = $LiveCandidateSha -match '^[A-Fa-f0-9]{64}$' -and
          $InstalledSha -match '^[A-Fa-f0-9]{64}$' -and
          $LiveCandidateSha -ceq $InstalledSha
        if (($CandidateHashCheck -ceq "PASS" -and -not $HashesMatch) -or
            ($CandidateHashCheck -ceq "FAIL" -and $HashesMatch) -or
            $CandidateHashCheck -notin @("PASS", "FAIL")) {
          Add-GateFailure "Uworld acceptance live candidate hash check must match recorded hashes"
        }

        $LiveStatus = if ($LiveValues.ContainsKey("status")) {
          ([string] $LiveValues["status"]).ToUpperInvariant()
        } else {
          ""
        }
        $DoctorResult = if ($LiveValues.ContainsKey("doctor_result")) {
          ([string] $LiveValues["doctor_result"]).ToUpperInvariant()
        } else {
          ""
        }
        if ($DoctorResult -notmatch '^UWORLD_ENVIRONMENT=(PASS|FAIL|UNVERIFIED)$' -or
            $LiveStatus -cne ($DoctorResult -replace '^UWORLD_ENVIRONMENT=', '')) {
          Add-GateFailure "Uworld acceptance live evidence must match environment doctor status"
        }
        if ($LiveStatus -ceq "PASS" -and
            ($CandidateHashCheck -cne "PASS" -or -not $HashesMatch)) {
          Add-GateFailure "Uworld acceptance live PASS requires the installed candidate hash"
        }

        $InstalledPath = if ($LiveValues.ContainsKey("installed_path")) {
          [string] $LiveValues["installed_path"]
        } else {
          ""
        }
        $ExpectedInstalledPath = [System.IO.Path]::GetFullPath(
          (Join-Path $VelocityHome "plugins\starx-velocity.jar"))
        if ([string]::IsNullOrWhiteSpace($InstalledPath)) {
          Add-GateFailure "Uworld acceptance live installed path must equal VelocityHome/plugins/starx-velocity.jar"
        } else {
          $ResolvedInstalledPath = if ([System.IO.Path]::IsPathRooted($InstalledPath)) {
            [System.IO.Path]::GetFullPath($InstalledPath)
          } else {
            [System.IO.Path]::GetFullPath((Join-Path $Root $InstalledPath))
          }
          $PathComparison = if (
            [System.IO.Path]::DirectorySeparatorChar -ne
              [System.IO.Path]::AltDirectorySeparatorChar
          ) {
            [System.StringComparison]::OrdinalIgnoreCase
          } else {
            [System.StringComparison]::Ordinal
          }
          if (-not [string]::Equals(
              $ResolvedInstalledPath,
              $ExpectedInstalledPath,
              $PathComparison)) {
            Add-GateFailure "Uworld acceptance live installed path must equal VelocityHome/plugins/starx-velocity.jar"
          }
          if (Test-Path -LiteralPath $ResolvedInstalledPath -PathType Leaf) {
            $ActualInstalledSha = (Get-FileHash -LiteralPath $ResolvedInstalledPath -Algorithm SHA256).Hash
            if ($ActualInstalledSha -cne $InstalledSha) {
              Add-GateFailure "Uworld acceptance recorded live installed SHA-256 must match installed artifact"
            }
          } else {
            Add-GateFailure "Uworld acceptance live installed artifact must exist"
          }
        }
        if ($LiveStatus -ceq "PASS") {
          $DoctorEvidence = if ($LiveValues.ContainsKey("doctor_evidence")) {
            [string] $LiveValues["doctor_evidence"]
          } else {
            ""
          }
          if (-not (Test-UworldDoctorEvidence `
              $DoctorEvidence `
              $CandidateSha `
              $InstalledSha `
              $VelocityHome `
              $ExpectedInstalledPath)) {
            Add-GateFailure "Uworld acceptance live PASS requires hashed environment doctor evidence"
          }
        }
      }

      if ($null -eq $RealClientSection) {
        Add-GateFailure "Uworld acceptance must contain a marked real-client matrix"
      } else {
        $ClientRows = [System.Collections.Generic.List[object]]::new()
        $ClientCaseIds = [System.Collections.Generic.List[string]]::new()
        $MalformedClientRow = $false
        foreach ($Line in $RealClientSection -split '\r?\n') {
          $TrimmedLine = $Line.Trim()
          if (-not $TrimmedLine.StartsWith('|') -or
              -not $TrimmedLine.EndsWith('|')) {
            continue
          }
          $RowBody = $TrimmedLine.Substring(1, $TrimmedLine.Length - 2)
          $Cells = @($RowBody -split '\|' | ForEach-Object { $_.Trim() })
          if ($Cells.Count -eq 8 -and
              $Cells[0] -ceq "Case" -and
              $Cells[7] -ceq "Status") {
            continue
          }
          if ($Cells.Count -eq 8 -and
              @($Cells | Where-Object { $_ -notmatch '^:?-{3,}:?$' }).Count -eq 0) {
            continue
          }
          if ($Cells.Count -ne 8) {
            $MalformedClientRow = $true
            continue
          }
          $CaseMatch = [regex]::Match(
            $Cells[0],
            '^(?<id>D(?:0[1-9]|1[01])|A(?:0[1-9]|1[0-4]))(?:\s|$)'
          )
          $CaseId = if ($CaseMatch.Success) {
            $CaseMatch.Groups["id"].Value
          } else {
            ""
          }
          if ($CaseId.Length -gt 0) {
            $ClientCaseIds.Add($CaseId)
          }
          $ClientRows.Add([pscustomobject]@{
            Cells = $Cells
            CaseId = $CaseId
          })
        }
        if ($MalformedClientRow) {
          Add-GateFailure "Uworld acceptance real-client rows must use exactly eight columns"
        }
        if ($ClientRows.Count -ne 25) {
          Add-GateFailure "Uworld acceptance real-client matrix must contain exactly 25 rows"
        }
        $RequiredCaseIds = @(
          1..11 | ForEach-Object { "D{0:D2}" -f $_ }
        ) + @(
          1..14 | ForEach-Object { "A{0:D2}" -f $_ }
        )
        $UniqueCaseIds = @($ClientCaseIds | Sort-Object -Unique)
        $CaseIdsComplete = $ClientCaseIds.Count -eq 25 -and
          $UniqueCaseIds.Count -eq 25 -and
          @($RequiredCaseIds | Where-Object { $_ -notin $UniqueCaseIds }).Count -eq 0
        if (-not $CaseIdsComplete) {
          Add-GateFailure "Uworld acceptance real-client matrix must contain each required case ID exactly once"
        }

        $PassEvidenceMissing = $false
        $PassCandidateMissing = $false
        $PassEvidenceFileMissing = $false
        $PassEvidenceBindingInvalid = $false
        $PassRuntimeIdentityInvalid = $false
        $PassExecutionBindingInvalid = $false
        $TimestampPattern = '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,7})?(?:Z|[+-]\d{2}:\d{2})$'
        foreach ($ClientRow in $ClientRows) {
          $Cells = $ClientRow.Cells
          $Status = $Cells[7].ToUpperInvariant()
          if ($Status -notin @("PASS", "FAIL", "UNVERIFIED")) {
            Add-GateFailure "Uworld acceptance real-client status must be PASS, FAIL, or UNVERIFIED"
            continue
          }
          if ($Status -cne "PASS") {
            continue
          }
          $Observed = $Cells[4]
          $Evidence = $Cells[5]
          $Timestamp = $Cells[6]
          [DateTimeOffset] $ParsedTimestamp = [DateTimeOffset]::MinValue
          $TimestampValid = $Timestamp -match $TimestampPattern -and
            [DateTimeOffset]::TryParse(
              $Timestamp,
              [System.Globalization.CultureInfo]::InvariantCulture,
              [System.Globalization.DateTimeStyles]::RoundtripKind,
              [ref] $ParsedTimestamp)
          if ((Test-UworldEvidencePlaceholder $Observed) -or
              (Test-UworldEvidencePlaceholder $Evidence) -or
              -not $TimestampValid) {
            $PassEvidenceMissing = $true
          }
          $EvidenceMatch = [regex]::Match(
            $Evidence,
            '^(?<path>[^;]+);\s*sha256=(?<sha>[A-Fa-f0-9]{64})$'
          )
          if (-not $EvidenceMatch.Success -or
              -not [string]::Equals(
                $EvidenceMatch.Groups["sha"].Value,
                $CandidateSha,
                [System.StringComparison]::OrdinalIgnoreCase)) {
            $PassCandidateMissing = $true
            continue
          }

          $EvidenceRelativePath = $EvidenceMatch.Groups["path"].Value.Trim()
          if ([System.IO.Path]::IsPathRooted($EvidenceRelativePath)) {
            $PassEvidenceBindingInvalid = $true
            continue
          }
          try {
            $ResolvedEvidencePath = [System.IO.Path]::GetFullPath(
              (Join-Path $Root $EvidenceRelativePath))
          } catch {
            $PassEvidenceBindingInvalid = $true
            continue
          }
          $EvidenceRootBoundary = $Root.TrimEnd(
            [System.IO.Path]::DirectorySeparatorChar,
            [System.IO.Path]::AltDirectorySeparatorChar) +
            [System.IO.Path]::DirectorySeparatorChar
          $EvidencePathComparison = if (
            [System.IO.Path]::DirectorySeparatorChar -ne
              [System.IO.Path]::AltDirectorySeparatorChar
          ) {
            [System.StringComparison]::OrdinalIgnoreCase
          } else {
            [System.StringComparison]::Ordinal
          }
          if (-not $ResolvedEvidencePath.StartsWith(
              $EvidenceRootBoundary,
              $EvidencePathComparison)) {
            $PassEvidenceBindingInvalid = $true
            continue
          }
          if (-not (Test-Path -LiteralPath $ResolvedEvidencePath -PathType Leaf)) {
            $PassEvidenceFileMissing = $true
            continue
          }
          $EvidenceValues = Convert-KeyValueSection (
            [System.IO.File]::ReadAllText($ResolvedEvidencePath))
          $CoreEvidenceBindingValid = $EvidenceValues.ContainsKey("artifact_sha256") -and
            [string]::Equals(
              [string] $EvidenceValues["artifact_sha256"],
              $CandidateSha,
              [System.StringComparison]::OrdinalIgnoreCase) -and
            $EvidenceValues.ContainsKey("case_id") -and
            [string] $EvidenceValues["case_id"] -ceq $ClientRow.CaseId -and
            $EvidenceValues.ContainsKey("timestamp") -and
            [string] $EvidenceValues["timestamp"] -ceq $Timestamp -and
            $EvidenceValues.ContainsKey("status") -and
            [string] $EvidenceValues["status"] -ceq "PASS"
          if (-not $CoreEvidenceBindingValid) {
            $PassEvidenceBindingInvalid = $true
            continue
          }

          $VelocityBuild = if ($EvidenceValues.ContainsKey("velocity_build")) {
            [string] $EvidenceValues["velocity_build"]
          } else {
            ""
          }
          $JavaVersion = if ($EvidenceValues.ContainsKey("java_version")) {
            [string] $EvidenceValues["java_version"]
          } else {
            ""
          }
          if ($VelocityBuild -cne "606" -or
              $JavaVersion -notmatch '^21(?:$|[._+-])') {
            $PassRuntimeIdentityInvalid = $true
          }

          $ExecutionBindingValid = $true
          foreach ($Field in @(
              "client_version",
              "account_type",
              "initial_server",
              "expected_target",
              "observed_outcome",
              "proxy_log")) {
            if (-not $EvidenceValues.ContainsKey($Field) -or
                (Test-UworldEvidencePlaceholder ([string] $EvidenceValues[$Field]))) {
              $ExecutionBindingValid = $false
            }
          }
          if (-not $EvidenceValues.ContainsKey("observed_outcome") -or
              [string] $EvidenceValues["observed_outcome"] -cne $Observed) {
            $ExecutionBindingValid = $false
          }

          $ProxyLogSha = if ($EvidenceValues.ContainsKey("proxy_log_sha256")) {
            [string] $EvidenceValues["proxy_log_sha256"]
          } else {
            ""
          }
          $ProxyLogRelativePath = if ($EvidenceValues.ContainsKey("proxy_log")) {
            [string] $EvidenceValues["proxy_log"]
          } else {
            ""
          }
          if ($ProxyLogSha -notmatch '^[A-Fa-f0-9]{64}$' -or
              [string]::IsNullOrWhiteSpace($ProxyLogRelativePath) -or
              [System.IO.Path]::IsPathRooted($ProxyLogRelativePath)) {
            $ExecutionBindingValid = $false
          } else {
            try {
              $ResolvedProxyLogPath = [System.IO.Path]::GetFullPath(
                (Join-Path $Root $ProxyLogRelativePath))
            } catch {
              $ResolvedProxyLogPath = $null
              $ExecutionBindingValid = $false
            }
            if ($null -ne $ResolvedProxyLogPath) {
              $ProxyEvidenceRoot = [System.IO.Path]::GetFullPath(
                (Join-Path $Root "docs\evidence"))
              $ProxyEvidenceBoundary = $ProxyEvidenceRoot.TrimEnd(
                [System.IO.Path]::DirectorySeparatorChar,
                [System.IO.Path]::AltDirectorySeparatorChar) +
                [System.IO.Path]::DirectorySeparatorChar
              $ProxyLogInEvidenceRoot = $ResolvedProxyLogPath.StartsWith(
                $ProxyEvidenceBoundary,
                $EvidencePathComparison)
              $ProxyLogIsEvidence = [string]::Equals(
                $ResolvedProxyLogPath,
                $ResolvedEvidencePath,
                $EvidencePathComparison)
              if (-not $ProxyLogInEvidenceRoot -or
                  $ProxyLogIsEvidence -or
                  -not (Test-Path -LiteralPath $ResolvedProxyLogPath -PathType Leaf)) {
                $ExecutionBindingValid = $false
              } else {
                $ProxyLog = Get-Item -LiteralPath $ResolvedProxyLogPath
                $ActualProxyLogSha = (Get-FileHash `
                  -LiteralPath $ResolvedProxyLogPath `
                  -Algorithm SHA256).Hash
                if ($ProxyLog.Length -le 0 -or
                    -not [string]::Equals(
                      $ActualProxyLogSha,
                      $ProxyLogSha,
                      [System.StringComparison]::OrdinalIgnoreCase)) {
                  $ExecutionBindingValid = $false
                } else {
                  $ProxyLogValues = Convert-KeyValueSection (
                    [System.IO.File]::ReadAllText($ResolvedProxyLogPath))
                  $ProxyEvent = if ($ProxyLogValues.ContainsKey("event")) {
                    [string] $ProxyLogValues["event"]
                  } else {
                    ""
                  }
                  $ProxyLogBindingValid = $ProxyLogValues.ContainsKey("artifact_sha256") -and
                    [string]::Equals(
                      [string] $ProxyLogValues["artifact_sha256"],
                      $CandidateSha,
                      [System.StringComparison]::OrdinalIgnoreCase) -and
                    $ProxyLogValues.ContainsKey("case_id") -and
                    [string] $ProxyLogValues["case_id"] -ceq $ClientRow.CaseId -and
                    $ProxyLogValues.ContainsKey("timestamp") -and
                    [string] $ProxyLogValues["timestamp"] -ceq $Timestamp -and
                    $ProxyLogValues.ContainsKey("observed_outcome") -and
                    [string] $ProxyLogValues["observed_outcome"] -ceq $Observed -and
                    -not (Test-UworldEvidencePlaceholder $ProxyEvent) -and
                    $ProxyEvent.IndexOf(
                      $ClientRow.CaseId,
                      [System.StringComparison]::Ordinal) -ge 0 -and
                    $ProxyEvent.IndexOf(
                      $Observed,
                      [System.StringComparison]::Ordinal) -ge 0
                  if (-not $ProxyLogBindingValid) {
                    $ExecutionBindingValid = $false
                  }
                }
              }
            }
          }
          if (-not $ExecutionBindingValid) {
            $PassExecutionBindingInvalid = $true
          }
        }
        if ($PassEvidenceMissing) {
          Add-GateFailure "Uworld acceptance PASS rows require observed, evidence, and ISO-8601 timestamp"
        }
        if ($PassCandidateMissing) {
          Add-GateFailure "Uworld acceptance PASS row evidence must bind the current candidate SHA-256"
        }
        if ($PassEvidenceFileMissing) {
          Add-GateFailure "Uworld acceptance PASS row evidence file must exist"
        }
        if ($PassEvidenceBindingInvalid) {
          Add-GateFailure "Uworld acceptance PASS row evidence file must bind its case, candidate, and timestamp"
        }
        if ($PassRuntimeIdentityInvalid) {
          Add-GateFailure "Uworld acceptance PASS row evidence must bind Velocity build and Java version"
        }
        if ($PassExecutionBindingInvalid) {
          Add-GateFailure "Uworld acceptance PASS row evidence must bind client, route, outcome, and proxy log"
        }
      }

      [long] $AutomaticSize = 0
      if (-not $AutomaticValues.ContainsKey("ARTIFACT_SIZE") -or
          -not [long]::TryParse([string] $AutomaticValues["ARTIFACT_SIZE"], [ref] $AutomaticSize) -or
          -not $CandidateSizeValid -or
          $AutomaticSize -ne $CandidateSize) {
        Add-GateFailure "Uworld acceptance evidence must use one candidate size"
      }

      if ($AutomaticValues.ContainsKey("status") -and
          [string] $AutomaticValues["status"] -ceq "PASS") {
        $JunitValid = $true
        $Aggregate = [ordered]@{
          Suites = 0L
          Tests = 0L
          Failures = 0L
          Errors = 0L
          Skipped = 0L
        }
        foreach ($Project in @(
            "starx-limbo-api",
            "starx-common",
            "starx-standalone-limbo",
            "starx-velocity")) {
          $Summary = Get-JunitSummary (Join-Path $Root "starx-plugins\$Project")
          $Pattern = '(?im)^' + [regex]::Escape($Project) +
            ':\s*(?<tests>\d+) tests,\s*(?<failures>\d+) failures,\s*' +
            '(?<errors>\d+) errors,\s*(?<skipped>\d+) skipped\s*$'
          $Recorded = [regex]::Match($AutomaticSection, $Pattern)
          if ($null -eq $Summary -or -not $Recorded.Success) {
            $JunitValid = $false
            continue
          }
          if ([long] $Recorded.Groups["tests"].Value -ne $Summary.Tests -or
              [long] $Recorded.Groups["failures"].Value -ne $Summary.Failures -or
              [long] $Recorded.Groups["errors"].Value -ne $Summary.Errors -or
              [long] $Recorded.Groups["skipped"].Value -ne $Summary.Skipped) {
            $JunitValid = $false
          }
          foreach ($Name in @("Suites", "Tests", "Failures", "Errors", "Skipped")) {
            $Aggregate[$Name] += $Summary.$Name
          }
        }
        $AggregatePattern = '(?im)^aggregate:\s*(?<suites>\d+) suites,\s*' +
          '(?<tests>\d+) tests,\s*(?<failures>\d+) failures,\s*' +
          '(?<errors>\d+) errors,\s*(?<skipped>\d+) skipped\s*$'
        $RecordedAggregate = [regex]::Match($AutomaticSection, $AggregatePattern)
        if (-not $RecordedAggregate.Success -or
            [long] $RecordedAggregate.Groups["suites"].Value -ne $Aggregate.Suites -or
            [long] $RecordedAggregate.Groups["tests"].Value -ne $Aggregate.Tests -or
            [long] $RecordedAggregate.Groups["failures"].Value -ne $Aggregate.Failures -or
            [long] $RecordedAggregate.Groups["errors"].Value -ne $Aggregate.Errors -or
            [long] $RecordedAggregate.Groups["skipped"].Value -ne $Aggregate.Skipped) {
          $JunitValid = $false
        }
        if (-not $JunitValid) {
          Add-GateFailure "Uworld acceptance JUnit evidence must match test-result XML"
        }
      }
    }
  }

  $EnvironmentDocument = Join-Path $Root "docs\UWORLD_ENVIRONMENT.md"
  if (Test-Path -LiteralPath $EnvironmentDocument -PathType Leaf) {
    $EnvironmentText = [System.IO.File]::ReadAllText($EnvironmentDocument)
    $OperationalTargetPattern = '(?is)(Windows Service.{0,180}Linux systemd|Windows.{0,180}Linux)' +
      '.{0,300}(statically validated|\u9759\u6001\u9a8c\u8bc1)' +
      '.{0,300}(staging.{0,80}production|staging/production)' +
      '.{0,120}UNVERIFIED'
    if ($EnvironmentText -notmatch $OperationalTargetPattern) {
      Add-GateFailure "Uworld environment must keep Windows and Linux deployment execution UNVERIFIED"
    }
    $ContradictoryPlatformLines = @($EnvironmentText -split '\r?\n' | Where-Object {
      $_ -match '(?i)Windows Service' -and
        $_ -match '(?i)Linux systemd' -and
        $_ -match '(?i)(supported production|certified production|\u53d7\u652f\u6301\u751f\u4ea7|\u5df2\u652f\u6301\u751f\u4ea7|\u5df2\u8ba4\u8bc1\u751f\u4ea7)' -and
        $_ -notmatch '(?i)(not|cannot|must not|UNVERIFIED|\u4e0d\u80fd|\u4e0d\u5f97|\u672a\u9a8c\u8bc1|\u5c1a\u672a)'
    })
    if ($ContradictoryPlatformLines.Count -ne 0) {
      Add-GateFailure "Uworld environment must not contradict its unverified platform status"
    }
    if ($EnvironmentText -match '(?m)^\s*\[\[server\]\]\s*$' -or
        $EnvironmentText -notmatch '(?m)^\s*\[servers\]\s*$') {
      Add-GateFailure "Uworld environment guide must use the canonical [servers] table"
    }
    $HasPrimaryModule = $EnvironmentText -match '(?m)^\s*starx\.uworld:\s*$'
    $HasPrimaryRoot = $EnvironmentText -match '(?m)^\s*uworld:\s*$'
    if (-not ($HasPrimaryModule -and $HasPrimaryRoot)) {
      Add-GateFailure "Uworld environment guide contains a legacy-only configuration example"
    }
    $HasDoctorCommand = $EnvironmentText -match '(?is)powershell.{0,300}check-uworld-environment\.ps1' -and
      $EnvironmentText -match '(?i)-VelocityHome\b' -and
      $EnvironmentText -match '(?i)-CandidateJar\b' -and
      $EnvironmentText -match '(?i)-ServiceIdentity\b'
    if (-not $HasDoctorCommand) {
      Add-GateFailure "Uworld environment guide must include the environment doctor command"
    }
    foreach ($IdentityCheck in @(
      "plugin_jar_inspection",
      "starx_jar_count",
      "external_limboapi",
      "candidate_hash"
    )) {
      if ($EnvironmentText -notmatch "(?m)^.*$([regex]::Escape($IdentityCheck)).*$") {
        Add-GateFailure "Uworld environment guide must require doctor check $IdentityCheck before shutdown"
      }
    }

    $PowerShellBlocks = @(Get-MarkdownCodeBlocks `
      $EnvironmentText `
      @("powershell", "pwsh"))
    $BashBlocks = @(Get-MarkdownCodeBlocks `
      $EnvironmentText `
      @("bash", "sh"))
    $AllCodeBlocks = @(Get-MarkdownCodeBlocks $EnvironmentText @())
    $PowerShellAsts = [System.Collections.Generic.List[object]]::new()
    $ParsedPowerShellBlocks = [System.Collections.Generic.List[object]]::new()
    for ($Index = 0; $Index -lt $PowerShellBlocks.Count; $Index++) {
      $Ast = Parse-PowerShellCode `
        $PowerShellBlocks[$Index].Code `
        "Uworld PowerShell block $($Index + 1)"
      if ($null -eq $Ast) {
        continue
      }
      $PowerShellAsts.Add($Ast)
      $ParsedPowerShellBlocks.Add([pscustomobject]@{
        Block = $PowerShellBlocks[$Index]
        Ast = $Ast
      })
      Test-PowerShellCommandSafety $Ast "Uworld PowerShell block $($Index + 1)"
    }

    $PowerShellDoctorInvocations = @($PowerShellAsts | ForEach-Object {
      $_.FindAll({
        param($Node)
        $Node -is [System.Management.Automation.Language.CommandAst] -and
        (Test-IsPowerShellDoctorInvocation $Node)
      }, $true)
    })
    foreach ($Invocation in $PowerShellDoctorInvocations) {
      if (-not (Test-PowerShellExplicitArgument $Invocation 'ServiceIdentity')) {
        Add-GateFailure "Every PowerShell Uworld doctor invocation must pass -ServiceIdentity"
      }
    }

    foreach ($BashBlock in $BashBlocks) {
      $VisibleBash = Get-BashVisibleCode $BashBlock.Code
      $BashDoctorInvocations = @(Get-BashTopLevelCommands $VisibleBash |
        Where-Object {
          $_.Kind -eq 'Command' -and
          (Test-IsBashDoctorInvocation $_.Text)
        })
      foreach ($Invocation in $BashDoctorInvocations) {
        if (-not (Test-BashDoctorInvocationIdentity $Invocation.Text)) {
          Add-GateFailure "Every Linux Uworld doctor invocation must pass -ServiceIdentity"
        }
      }
    }

    $IcaclsHelpers = @($PowerShellAsts | ForEach-Object {
      $_.FindAll({
        param($Node)
        $Node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
        $Node.Name -ieq "Invoke-Icacls"
      }, $true)
    })
    if ($IcaclsHelpers.Count -eq 0) {
      Add-GateFailure "Windows examples must define fail-fast Invoke-Icacls"
    }
    foreach ($Helper in $IcaclsHelpers) {
      if (-not (Test-IcaclsHelper $Helper)) {
        Add-GateFailure "Invoke-Icacls must execute icacls and reject nonzero exit codes"
      }
    }

    $WindowsBlock = Get-MarkedCodeBlock `
      $EnvironmentText `
      "UWORLD_WINDOWS_DEPLOYMENT" `
      @("powershell", "pwsh")
    if ($null -eq $WindowsBlock) {
      Add-GateFailure "Uworld environment guide must contain one marked Windows deployment block"
    } else {
      $WindowsAst = Parse-PowerShellCode `
        $WindowsBlock.Code `
        "Marked Windows deployment block"
      if ($null -ne $WindowsAst) {
        $Commands = @(Get-TopLevelCommands $WindowsAst)
        $AllWindowsCommands = @($WindowsAst.FindAll({
          param($Node)
          $Node -is [System.Management.Automation.Language.CommandAst]
        }, $true))
        $WindowsFunctions = @($WindowsAst.FindAll({
          param($Node)
          $Node -is [System.Management.Automation.Language.FunctionDefinitionAst]
        }, $true))
        $UnexpectedWindowsFunctions = @($WindowsFunctions | Where-Object {
          $_.Name -notin @(
            'Invoke-UworldDoctor',
            'Invoke-Icacls',
            'Assert-UworldJarIdentity',
            'Assert-UworldEnvironment')
        })
        $WindowsErrorPreferences = @($WindowsAst.FindAll({
          param($Node)
          $Node -is
            [System.Management.Automation.Language.AssignmentStatementAst] -and
          $Node.Left -is
            [System.Management.Automation.Language.VariableExpressionAst] -and
          $Node.Left.VariablePath.UserPath -match
            '(?i)(?:^|:)ErrorActionPreference$'
        }, $true) | Where-Object {
          Test-IsRootPowerShellStatement $_
        })
        $WindowsTraps = @($WindowsAst.FindAll({
          param($Node)
          $Node -is [System.Management.Automation.Language.TrapStatementAst]
        }, $true))
        $HasDefaultParameterOverride =
          $WindowsBlock.Code -match '(?i)\$PSDefaultParameterValues'
        $ForbiddenStateCommands = @($AllWindowsCommands | Where-Object {
          $Name = Get-NormalizedPowerShellCommandName $_
          $AliasTarget = Get-PowerShellAliasTargetName $_
          $Name -in @(
            'Set-Variable',
            'Set-Item',
            'Clear-Variable',
            'Remove-Variable',
            'Set-Alias',
            'New-Alias',
            'Remove-Alias') -or
          $AliasTarget -in @(
            'Set-Variable',
            'Set-Item',
            'Clear-Variable',
            'Remove-Variable',
            'Set-Alias',
            'New-Alias',
            'Remove-Alias')
        })
        $ProviderOverrides = @($AllWindowsCommands | Where-Object {
          $_.Extent.Text -match
            '(?i)(?:Function|Alias):(?:[^\s\\/]+[\\/])?(?:Stop-Service|Start-Service|Copy-Item|Move-Item|icacls)\b'
        })
        $ScopedPreferenceAssignments = @($WindowsAst.FindAll({
          param($Node)
          $Node -is [System.Management.Automation.Language.AssignmentStatementAst] -and
          $Node.Left -is [System.Management.Automation.Language.VariableExpressionAst] -and
          $Node.Left.VariablePath.UserPath -match
            '(?i)^(?:script|global|local|private):(?:ErrorActionPreference|PSDefaultParameterValues)$'
        }, $true))
        $DoctorRunners = @($WindowsFunctions | Where-Object {
          $_.Name -ieq 'Invoke-UworldDoctor'
        })
        $IdentityAssertions = @($WindowsFunctions | Where-Object {
          $_.Name -ieq 'Assert-UworldJarIdentity'
        })
        $EnvironmentAssertions = @($WindowsFunctions | Where-Object {
          $_.Name -ieq 'Assert-UworldEnvironment'
        })
        if ($DoctorRunners.Count -eq 1 -and
            -not (Test-PowerShellDoctorRunnerIdentity $DoctorRunners[0].Extent.Text)) {
          Add-GateFailure "Windows Uworld doctor runner must pass -ServiceIdentity"
        }
        $HasDoctorDefinitions = $DoctorRunners.Count -eq 1 -and
          $UnexpectedWindowsFunctions.Count -eq 0 -and
          $IdentityAssertions.Count -eq 1 -and
          $EnvironmentAssertions.Count -eq 1 -and
          (Test-PowerShellDoctorRunner $DoctorRunners[0]) -and
          (Test-PowerShellDoctorAssertion $IdentityAssertions[0] $false) -and
          (Test-PowerShellDoctorAssertion $EnvironmentAssertions[0] $true)
        $TopLevelEarlyExits = @($WindowsAst.FindAll({
          param($Node)
          $Node -is [System.Management.Automation.Language.ReturnStatementAst] -or
            $Node -is [System.Management.Automation.Language.ExitStatementAst]
        }, $true) | Where-Object {
          $null -eq (Get-ContainingFunction $_)
        })
        $HasFailFastErrorPreference =
          $WindowsErrorPreferences.Count -eq 1 -and
          $WindowsErrorPreferences[0].Extent.Text -match
            '(?i)^\s*\$ErrorActionPreference\s*=\s*(?:''Stop''|"Stop")\s*$' -and
          $WindowsTraps.Count -eq 0 -and
          -not $HasDefaultParameterOverride -and
          $ForbiddenStateCommands.Count -eq 0 -and
          $ProviderOverrides.Count -eq 0 -and
          $ScopedPreferenceAssignments.Count -eq 0
        $WindowsIdentity = @($Commands | Where-Object {
          (Get-NormalizedPowerShellCommandName $_) -ieq
            "Assert-UworldJarIdentity" -and
          (Test-PowerShellSingleArgument $_ '$CurrentJar')
        })
        $WindowsStop = @($Commands | Where-Object {
          (Get-NormalizedPowerShellCommandName $_) -ieq "Stop-Service" -and
          (Test-PowerShellBoundParameterValue `
            $_ `
            'Name' `
            '$VelocityService') -and
          (Test-PowerShellDeploymentParameterSet $_ @('Name'))
        })
        $WindowsIcaclsAll = @($Commands | Where-Object {
          (Get-NormalizedPowerShellCommandName $_) -ieq 'Invoke-Icacls'
        })
        $WindowsIcacls = @($WindowsIcaclsAll | Where-Object {
          $null -ne (Get-PowerShellIcaclsArgumentKind $_)
        })
        $WindowsIcaclsKinds = @($WindowsIcacls | ForEach-Object {
          Get-PowerShellIcaclsArgumentKind $_
        })
        $WindowsInstall = @($Commands | Where-Object {
          (Get-NormalizedPowerShellCommandName $_) -ieq "Copy-Item" -and
          (Test-PowerShellBoundParameterValue `
            $_ `
            'LiteralPath' `
            '$CandidateJar') -and
          (Get-PowerShellBoundParameterText $_ 'Destination') -match
            '\$PluginDir\b[\s\S]*starx-velocity\.jar' -and
          (Test-PowerShellDeploymentParameterSet `
            $_ `
            @('LiteralPath', 'Destination'))
        })
        $WindowsCandidateMutations = @($Commands | Where-Object {
          (Get-NormalizedPowerShellCommandName $_) -in @('Copy-Item', 'Move-Item') -and
          $_.Extent.Text -match '\$CandidateJar\b'
        })
        $WindowsEnvironment = @($Commands | Where-Object {
          (Get-NormalizedPowerShellCommandName $_) -ieq
            "Assert-UworldEnvironment" -and
          (Test-PowerShellSingleArgument $_ '$CandidateJar')
        })
        $WindowsStart = @($Commands | Where-Object {
          (Get-NormalizedPowerShellCommandName $_) -ieq "Start-Service" -and
          (Test-PowerShellBoundParameterValue `
            $_ `
            'Name' `
            '$VelocityService') -and
          (Test-PowerShellDeploymentParameterSet $_ @('Name'))
        })
        $HasWindowsOrder = $HasFailFastErrorPreference -and
          $HasDoctorDefinitions -and
          $TopLevelEarlyExits.Count -eq 0 -and
          $WindowsIdentity.Count -eq 1 -and
          $WindowsIcaclsAll.Count -eq 3 -and
          $WindowsIcacls.Count -eq 3 -and
          @($WindowsIcaclsKinds | Where-Object { $_ -eq 'reset' }).Count -eq 1 -and
          @($WindowsIcaclsKinds | Where-Object { $_ -eq 'inheritance' }).Count -eq 1 -and
          @($WindowsIcaclsKinds | Where-Object { $_ -eq 'grant' }).Count -eq 1 -and
          $WindowsStop.Count -eq 1 -and
          $WindowsInstall.Count -eq 1 -and
          $WindowsCandidateMutations.Count -eq 1 -and
          $WindowsCandidateMutations[0].Extent.StartOffset -eq
            $WindowsInstall[0].Extent.StartOffset -and
          $WindowsEnvironment.Count -eq 1 -and
          $WindowsStart.Count -eq 1 -and
          $WindowsErrorPreferences[0].Extent.StartOffset -lt
            $WindowsIdentity[0].Extent.StartOffset -and
          @($WindowsIcacls | Where-Object {
            $_.Extent.StartOffset -le $WindowsIdentity[0].Extent.StartOffset -or
            $_.Extent.StartOffset -ge $WindowsStop[0].Extent.StartOffset
          }).Count -eq 0 -and
          $WindowsIdentity[0].Extent.StartOffset -lt $WindowsStop[0].Extent.StartOffset -and
          $WindowsStop[0].Extent.StartOffset -lt $WindowsInstall[0].Extent.StartOffset -and
          $WindowsInstall[0].Extent.StartOffset -lt $WindowsEnvironment[0].Extent.StartOffset -and
          $WindowsEnvironment[0].Extent.StartOffset -lt $WindowsStart[0].Extent.StartOffset
        if (-not $HasWindowsOrder) {
          Add-GateFailure "Marked Windows deployment must run identity before stop and full doctor before start"
        }
      }
    }

    $LinuxBlock = Get-MarkedCodeBlock `
      $EnvironmentText `
      "UWORLD_LINUX_DEPLOYMENT" `
      @("bash", "sh")
    if ($null -eq $LinuxBlock) {
      Add-GateFailure "Uworld environment guide must contain one marked Linux deployment block"
    } else {
      $LinuxCommands = @(Get-BashTopLevelCommands $LinuxBlock.Code)
      $LinuxExecutableCommands = @($LinuxCommands | Where-Object {
        $_.Kind -eq 'Command'
      })
      $LinuxFunctionDefinitions = @($LinuxCommands | Where-Object {
        $_.Kind -eq 'FunctionDefinition'
      })
      $LinuxFunctionNames = @($LinuxFunctionDefinitions.Name | Sort-Object)
      $HasExpectedLinuxFunctions = $LinuxFunctionNames.Count -eq 3 -and
        $LinuxFunctionNames[0] -ceq 'assert_uworld_environment' -and
        $LinuxFunctionNames[1] -ceq 'assert_uworld_jar_identity' -and
        $LinuxFunctionNames[2] -ceq 'run_uworld_doctor'
      if ($HasExpectedLinuxFunctions -and
          -not (Test-BashDoctorRunnerIdentity $LinuxBlock.Code)) {
        Add-GateFailure "Linux Uworld doctor runner must pass -ServiceIdentity"
      }
      $LinuxProtectedDefinitions = @($LinuxCommands | Where-Object {
        $_.Kind -eq 'FunctionDefinition' -and
        $_.Name -in @('set', 'eval', 'install', 'systemctl')
      })
      if ($LinuxProtectedDefinitions.Count -ne 0) {
        Add-GateFailure "Marked Linux deployment must not override protected commands"
      }
      $LinuxForbiddenCommands = @($LinuxExecutableCommands | Where-Object {
        $Name = Get-BashEffectiveCommandName $_.Text
        $Parts = @($Name -split '[\\/]')
        $Parts[-1] -in @(
          'eval',
          'alias',
          'unalias',
          'enable',
          'shopt',
          'source',
          '.',
          'hash')
      })
      $LinuxSetCommands = @($LinuxCommands | Where-Object {
        Test-BashSetInvocation $_.Text
      })
      $LinuxEvalCommands = @($LinuxCommands | Where-Object {
        Test-BashEvalInvocation $_.Text
      })
      $LinuxFailFast = @($LinuxSetCommands | Where-Object {
        (Test-BashStandaloneCommand $_) -and
        $_.Text -match '^set\s+-euo\s+pipefail\s*$'
      })
      $LinuxEarlyExits = @($LinuxExecutableCommands | Where-Object {
        $Name = Get-BashEffectiveCommandName $_.Text
        $Parts = @($Name -split '[\\/]')
        $Leaf = $Parts[-1]
        $Leaf -in @('exit', 'return') -and
        $_.Text -notmatch '^exit\s+1\s*$'
      })
      $LinuxIdentity = @($LinuxCommands | Where-Object {
        (Test-BashStandaloneCommand $_) -and
        $_.Text -match
          '^assert_uworld_jar_identity\s+"\$current_jar"\s*$'
      })
      $LinuxStop = @($LinuxCommands | Where-Object {
        (Test-BashStandaloneCommand $_) -and
        $_.Text -match
          '^systemctl\s+stop\s+"\$VELOCITY_SERVICE"\s*$'
      })
      $LinuxInstall = @($LinuxCommands | Where-Object {
        (Test-BashStandaloneCommand $_) -and
        (Test-BashCandidateInstall $_.Text)
      })
      $LinuxCandidateMutations = @($LinuxExecutableCommands | Where-Object {
        Test-BashCandidateMutation $_.Text
      })
      $LinuxEnvironment = @($LinuxCommands | Where-Object {
        (Test-BashStandaloneCommand $_) -and
        $_.Text -match
          '^assert_uworld_environment\s+"\$RELEASE_JAR"\s*$'
      })
      $LinuxStart = @($LinuxCommands | Where-Object {
        (Test-BashStandaloneCommand $_) -and
        $_.Text -match
          '^systemctl\s+start\s+"\$VELOCITY_SERVICE"\s*$'
      })
      $HasLinuxOrder = $LinuxProtectedDefinitions.Count -eq 0 -and
        $HasExpectedLinuxFunctions -and
        $LinuxForbiddenCommands.Count -eq 0 -and
        $LinuxEarlyExits.Count -eq 0 -and
        (Test-BashDoctorDefinitions $LinuxBlock.Code) -and
        $LinuxExecutableCommands.Count -gt 0 -and
        $LinuxSetCommands.Count -eq 1 -and
        $LinuxEvalCommands.Count -eq 0 -and
        $LinuxFailFast.Count -eq 1 -and
        $LinuxExecutableCommands[0].StartOffset -eq
          $LinuxFailFast[0].StartOffset -and
        $LinuxIdentity.Count -eq 1 -and
        $LinuxStop.Count -eq 1 -and
        $LinuxInstall.Count -eq 1 -and
        $LinuxCandidateMutations.Count -eq 1 -and
        $LinuxCandidateMutations[0].StartOffset -eq
          $LinuxInstall[0].StartOffset -and
        $LinuxEnvironment.Count -eq 1 -and
        $LinuxStart.Count -eq 1 -and
        $LinuxFailFast[0].StartOffset -lt $LinuxIdentity[0].StartOffset -and
        $LinuxIdentity[0].StartOffset -lt $LinuxStop[0].StartOffset -and
        $LinuxStop[0].StartOffset -lt $LinuxInstall[0].StartOffset -and
        $LinuxInstall[0].StartOffset -lt $LinuxEnvironment[0].StartOffset -and
        $LinuxEnvironment[0].StartOffset -lt $LinuxStart[0].StartOffset
      if (-not $HasLinuxOrder) {
        Add-GateFailure "Marked Linux deployment must run identity before stop and full doctor before start"
      }
    }

    $LinuxRollbackBlock = Get-MarkedCodeBlock `
      $EnvironmentText `
      "UWORLD_LINUX_ROLLBACK" `
      @("bash", "sh")
    if ($null -eq $LinuxRollbackBlock) {
      Add-GateFailure "Uworld environment guide must contain one marked Linux rollback block"
    } else {
      $RollbackCommands = @(Get-BashTopLevelCommands $LinuxRollbackBlock.Code)
      $RollbackExecutableCommands = @($RollbackCommands | Where-Object {
        $_.Kind -eq 'Command'
      })
      $RollbackSetCommands = @($RollbackCommands | Where-Object {
        Test-BashSetInvocation $_.Text
      })
      $RollbackFailFast = @($RollbackSetCommands | Where-Object {
        (Test-BashStandaloneCommand $_) -and
        $_.Text -match '^set\s+-euo\s+pipefail\s*$'
      })
      $HasRollbackFailFast = $RollbackExecutableCommands.Count -gt 0 -and
        $RollbackSetCommands.Count -eq 1 -and
        $RollbackFailFast.Count -eq 1 -and
        $RollbackExecutableCommands[0].StartOffset -eq
          $RollbackFailFast[0].StartOffset
      if (-not $HasRollbackFailFast) {
        Add-GateFailure "Marked Linux rollback must enable fail-fast before its first command"
      }
    }

    foreach ($CodeBlock in $AllCodeBlocks) {
      $IsMarkedDeployment = ($null -ne $WindowsBlock -and
          $CodeBlock.StartOffset -eq $WindowsBlock.StartOffset) -or
        ($null -ne $LinuxBlock -and
          $CodeBlock.StartOffset -eq $LinuxBlock.StartOffset)
      if ($IsMarkedDeployment) {
        continue
      }
      $HasWindowsCandidateInstall =
        $CodeBlock.Code -match '(?i)\bCopy-Item\b' -and
        $CodeBlock.Code -match '\$CandidateJar\b' -and
        $CodeBlock.Code -match 'starx-velocity\.jar'
      $HasLinuxCandidateInstall =
        Test-BashPotentialCandidateInstall $CodeBlock.Code
      if ($HasWindowsCandidateInstall -or $HasLinuxCandidateInstall) {
        Add-GateFailure "Candidate installation must use the marked deployment block"
      }
    }

    foreach ($ParsedBlock in $ParsedPowerShellBlocks) {
      $IsMarkedWindows = $null -ne $WindowsBlock -and
        $ParsedBlock.Block.StartOffset -eq $WindowsBlock.StartOffset
      if ($IsMarkedWindows) {
        continue
      }
      $Commands = @(Get-TopLevelCommands $ParsedBlock.Ast)
      $HasStop = @($Commands | Where-Object {
        (Get-NormalizedPowerShellCommandName $_) -ieq 'Stop-Service'
      }).Count -gt 0
      $HasStart = @($Commands | Where-Object {
        (Get-NormalizedPowerShellCommandName $_) -ieq 'Start-Service'
      }).Count -gt 0
      $HasCandidate = @($Commands | Where-Object {
        $_.Extent.Text -match '\$CandidateJar\b' -or
        (Get-NormalizedPowerShellCommandName $_) -in @(
          'Assert-UworldJarIdentity',
          'Assert-UworldEnvironment')
      }).Count -gt 0
      $HasInstall = @($Commands | Where-Object {
        ((Get-NormalizedPowerShellCommandName $_) -in @(
            'Copy-Item',
            'Move-Item') -and
          $_.Extent.Text -match 'starx-velocity\.jar') -or
        (Get-NormalizedPowerShellCommandName $_) -ieq
          'Assert-UworldEnvironment'
      }).Count -gt 0
      if (($HasStop -or $HasStart) -and $HasCandidate -and $HasInstall) {
        Add-GateFailure "Windows deployment examples must use the marked deployment block"
      }
    }

    foreach ($BashBlock in $BashBlocks) {
      $IsMarkedLinux = $null -ne $LinuxBlock -and
        $BashBlock.StartOffset -eq $LinuxBlock.StartOffset
      if ($IsMarkedLinux) {
        continue
      }
      $Commands = @(Get-BashTopLevelCommands $BashBlock.Code)
      $HasStop = @($Commands | Where-Object {
        $_.Text -match '^systemctl\s+stop\b'
      }).Count -gt 0
      $HasStart = @($Commands | Where-Object {
        $_.Text -match '^systemctl\s+start\b'
      }).Count -gt 0
      $HasCandidate = @($Commands | Where-Object {
        $_.Text -match '\$RELEASE_JAR\b' -or
        $_.Text -match '^assert_uworld_(?:jar_identity|environment)\b'
      }).Count -gt 0
      $HasInstall = @($Commands | Where-Object {
        (Test-BashCandidateInstall $_.Text) -or
        $_.Text -match '^assert_uworld_environment\b'
      }).Count -gt 0
      if (($HasStop -or $HasStart) -and $HasCandidate -and $HasInstall) {
        Add-GateFailure "Linux deployment examples must use the marked deployment block"
      }
    }

    $UsesFilenameJarSelection =
      $EnvironmentText -match '(?i)\.Name\s+-match[^\r\n]*(?:starx|limboapi)' -or
      $EnvironmentText -match '(?i)-iname\s+[''"](?:starx\*\.jar|\*limboapi\*\.jar)[''"]'
    if ($UsesFilenameJarSelection) {
      Add-GateFailure "Uworld deployment guide must not select StarX or LimboAPI JARs by filename"
    }
    if ($EnvironmentText -notmatch '(?i)modern' -or
        $EnvironmentText -notmatch '(?i)online-mode\s*[=:]\s*false') {
      Add-GateFailure "Uworld environment guide must document modern forwarding and Paper online-mode=false"
    }
    foreach ($DatabaseSuffix in @(".db", "-wal", "-shm")) {
      if (-not $EnvironmentText.Contains($DatabaseSuffix)) {
        Add-GateFailure "Uworld environment guide must cover SQLite $DatabaseSuffix backup"
      }
    }
  }
}

if ($DocumentationOnly) {
  Test-Documentation
  if ($Failures.Count -ne 0) {
    Write-Host "UWORLD_DOCUMENTATION_GATE=FAIL failures=$($Failures.Count)"
    exit 1
  }
  Write-Host "UWORLD_DOCUMENTATION_GATE=PASS"
  exit 0
}

Test-Metadata
if ($Failures.Count -ne 0) {
  Write-Host "UWORLD_METADATA_GATE=FAIL failures=$($Failures.Count)"
  exit 1
}
if ($MetadataOnly) {
  Write-Host "UWORLD_METADATA_GATE=PASS version=$(Get-ProjectVersion)"
  exit 0
}

if (-not $StaticOnly) {
  if (-not $SkipBuild) {
    Invoke-FreshBuild
  }
  Test-JunitResults
}

$Artifact = Test-Jar
Test-SourceRules
Test-Documentation

if ($Failures.Count -ne 0) {
  Write-Host "UWORLD_GATE=FAIL failures=$($Failures.Count)"
  exit 1
}

$Hash = Get-FileHash -LiteralPath $Artifact.FullName -Algorithm SHA256
Write-Host "ARTIFACT=$($Artifact.FullName)"
Write-Host "ARTIFACT_SIZE=$($Artifact.Length)"
Write-Host "ARTIFACT_SHA256=$($Hash.Hash)"
Write-Host "UWORLD_GATE=PASS"
