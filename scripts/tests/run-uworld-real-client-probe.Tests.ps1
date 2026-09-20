[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$ProbeScript = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot `
      '..\..\tmp\uworld-real-client-probe\run-probe.ps1'))
$Tokens = $null
$ParseErrors = $null
$Ast = [Management.Automation.Language.Parser]::ParseFile(
  $ProbeScript,
  [ref] $Tokens,
  [ref] $ParseErrors
)
if ($ParseErrors.Count -ne 0) {
  throw "Probe script has parser errors: $($ParseErrors -join '; ')"
}

$ParameterNames = @($Ast.ParamBlock.Parameters | ForEach-Object {
    $_.Name.VariablePath.UserPath
  })
foreach ($RequiredPid in @('ProtectedVelocityPid', 'ProtectedPaperPid')) {
  if ($ParameterNames -notcontains $RequiredPid) {
    throw "Probe script must require $RequiredPid instead of relying on a stale process id"
  }
}
$ProbeText = [IO.File]::ReadAllText($ProbeScript)
if ($ProbeText.Contains('-Dcom.mojang.eula.agree=true')) {
  throw 'Probe script must use its eula.txt fixture instead of emitting the command-line EULA error banner'
}
foreach ($RequiredPid in @('ProtectedVelocityPid', 'ProtectedPaperPid')) {
  $RequiredPattern = '(?s)\[Parameter\(Mandatory\s*=\s*\$true\)\].{0,120}\[int\]\s*\$' +
    [regex]::Escape($RequiredPid)
  if ($ProbeText -notmatch $RequiredPattern) {
    throw "Probe script must make $RequiredPid mandatory"
  }
}

$CleanupHelper = $Ast.Find({
    param($Node)
    $Node -is [Management.Automation.Language.FunctionDefinitionAst] -and
      $Node.Name -eq 'Invoke-CleanupActions'
  }, $true)
if ($null -ne $CleanupHelper) {
  Invoke-Expression $CleanupHelper.Extent.Text
}

$ProbeTry = @($Ast.FindAll({
      param($Node)
      $Node -is [Management.Automation.Language.TryStatementAst] -and
        $null -ne $Node.Finally -and
        $Node.Finally.Extent.Text -match 'Stop-OwnedProcess\s+\$velocityProcess'
    }, $true))
if ($ProbeTry.Count -ne 1) {
  throw "Expected one probe cleanup block, found $($ProbeTry.Count)"
}

$FinallyText = $ProbeTry[0].Finally.Extent.Text
$FinallyBody = $FinallyText.Substring(1, $FinallyText.Length - 2)
$Trace = [Collections.Generic.List[string]]::new()
$ExpectedStart = [datetime] '2026-07-15T01:02:03Z'

function Stop-OwnedProcess($Process, [string] $Label) {
  $Trace.Add("stop:$Label")
  if ($Label -eq 'Velocity') {
    throw [InvalidOperationException]::new('velocity cleanup failed')
  }
}

function Get-Process {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [int] $Id
  )

  $Trace.Add("assert-pid:$Id")
  return [pscustomobject] @{ StartTime = $ExpectedStart }
}

function Test-PortListening([int] $Port) {
  $Trace.Add("assert-port:$Port")
  return $false
}

$ProtectedVelocityPid = 83916
$ProtectedPaperPid = 73092
$Ports = @(25581, 8791, 25583)
$velocityProcess = 'velocity-owner'
$paperProcess = 'paper-owner'
$protectedVelocityStart = $ExpectedStart
$protectedPaperStart = $ExpectedStart
$protectedVelocityBefore = [pscustomobject] @{ StartTime = $ExpectedStart }
$protectedPaperBefore = [pscustomobject] @{ StartTime = $ExpectedStart }
$oldArtifactSha = 'before-probe'
$oldScenario = 'before-scenario'
$oldReadyFile = 'before-ready'
$oldContinueFile = 'before-continue'
$runRoot = 'synthetic-run-root'
$PreviousArtifactSha = $env:UWORLD_ARTIFACT_SHA
$PreviousScenario = $env:UWORLD_PROBE_SCENARIO
$PreviousReadyFile = $env:UWORLD_PROBE_READY_FILE
$PreviousContinueFile = $env:UWORLD_PROBE_CONTINUE_FILE
$env:UWORLD_ARTIFACT_SHA = 'during-probe'
$env:UWORLD_PROBE_SCENARIO = 'during-scenario'
$env:UWORLD_PROBE_READY_FILE = 'during-ready'
$env:UWORLD_PROBE_CONTINUE_FILE = 'during-continue'
$Caught = $null
try {
  try {
    Invoke-Expression $FinallyBody
  } catch {
    $Caught = $_.Exception
  }

  $ExpectedTrace = @(
    'stop:Velocity'
    'stop:Paper'
    'assert-pid:83916'
    'assert-pid:73092'
    'assert-port:25581'
    'assert-port:8791'
    'assert-port:25583'
  )
  $ActualTrace = @($Trace)
  if (($ActualTrace -join '|') -cne ($ExpectedTrace -join '|')) {
    throw "Cleanup did not attempt every step after the first failure. Expected=[$($ExpectedTrace -join ', ')] Actual=[$($ActualTrace -join ', ')]"
  }
  if ($env:UWORLD_ARTIFACT_SHA -cne $oldArtifactSha) {
    throw 'Cleanup did not restore UWORLD_ARTIFACT_SHA'
  }
  if ($env:UWORLD_PROBE_SCENARIO -cne $oldScenario) {
    throw 'Cleanup did not restore UWORLD_PROBE_SCENARIO'
  }
  if ($env:UWORLD_PROBE_READY_FILE -cne $oldReadyFile) {
    throw 'Cleanup did not restore UWORLD_PROBE_READY_FILE'
  }
  if ($env:UWORLD_PROBE_CONTINUE_FILE -cne $oldContinueFile) {
    throw 'Cleanup did not restore UWORLD_PROBE_CONTINUE_FILE'
  }
  if ($null -eq $Caught) {
    throw 'Cleanup failures were not reported'
  }
  if ($Caught -isnot [AggregateException]) {
    throw "Cleanup did not throw AggregateException: $($Caught.GetType().FullName)"
  }
  if ($Caught.InnerExceptions.Count -ne 1 -or
      $Caught.InnerExceptions[0].Message -cne 'velocity cleanup failed') {
    throw "Cleanup aggregate did not retain the original failure: $Caught"
  }
} finally {
  $env:UWORLD_ARTIFACT_SHA = $PreviousArtifactSha
  $env:UWORLD_PROBE_SCENARIO = $PreviousScenario
  $env:UWORLD_PROBE_READY_FILE = $PreviousReadyFile
  $env:UWORLD_PROBE_CONTINUE_FILE = $PreviousContinueFile
}

Write-Host 'PASS: isolated probe cleanup attempts every step and aggregates failures'
