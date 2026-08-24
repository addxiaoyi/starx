[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$Root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$Smoke = Join-Path $Root 'scripts\tests\run-starx-bridge-smoke.ps1'

if (-not (Test-Path -LiteralPath $Smoke -PathType Leaf)) {
  throw "Bridge smoke script is missing: $Smoke"
}

$Tokens = $null
$ParseErrors = $null
[void] [Management.Automation.Language.Parser]::ParseFile(
  $Smoke,
  [ref] $Tokens,
  [ref] $ParseErrors
)
if ($ParseErrors.Count -ne 0) {
  throw "Bridge smoke script has parse errors: $($ParseErrors.Message -join '; ')"
}

$Text = [IO.File]::ReadAllText($Smoke)
$RequiredPatterns = [ordered]@{
  'cmd wrapper' = '(?m)\.FileName\s*=\s*\$env:ComSpec\s*$'
  'shell flags' = '(?m)\.Arguments\s*=\s*.*?/d\s+/s\s+/c'
  'combined file logging' = '(?m)\.Arguments\s*=\s*.*?>.*?2>&1'
  'tree cleanup' = '(?m)&\s+taskkill\.exe\s+/PID\s+\$Process\.Id\s+/T\s+/F'
  'taskkill race output suppression' = '(?m)taskkill\.exe\s+/PID\s+\$Process\.Id\s+/T\s+/F\s+2>\$null'
  'Paper backend status command' = '(?m)\$PaperProcess\.StandardInput\.WriteLine\(''starxserver status''\)'
  'Paper proxy-contact timestamp' = '(?m)Wait-Log\s+\$PaperProcess\s+\$PaperLog\s+''Last proxy contact: \(\?!not-seen\\b\)\\d\{4\}-'''
  'Paper Java ownership' = '(?m)\$PaperJavaProcess\s*=\s*Resolve-JavaChild\s+\$PaperProcess\s+''paper\.jar'''
  'Velocity Java ownership' = '(?m)\$VelocityJavaProcess\s*=\s*Resolve-JavaChild\s+\$VelocityProcess\s+''velocity\.jar'''
  'native Java ownership' = '(?ms)Get-Process\s+-Name\s+''java''.*?\$_\.Parent\.Id\s+-eq\s+\$HostProcess\.Id'
  'Paper Java cleanup' = '(?m)Invoke-Cleanup\s+''Paper Java''\s+\{\s*Stop-ProcessTree\s+\$PaperJavaProcess\s*\}'
  'Velocity Java cleanup' = '(?m)Invoke-Cleanup\s+''Velocity Java''\s+\{\s*Stop-ProcessTree\s+\$VelocityJavaProcess\s*\}'
}

foreach ($Requirement in $RequiredPatterns.GetEnumerator()) {
  if ($Text -notmatch $Requirement.Value) {
    throw "Bridge smoke script is missing $($Requirement.Key)"
  }
}

foreach ($Forbidden in @(
  'Get-CimInstance',
  'OutputDataReceived',
  'ErrorDataReceived',
  'BeginOutputReadLine',
  'BeginErrorReadLine'
)) {
  if ($Text.Contains($Forbidden)) {
    throw "Bridge smoke script still uses asynchronous output callback: $Forbidden"
  }
}

Write-Host 'PASS: StarX bridge smoke uses file-backed logging and process-tree cleanup'
