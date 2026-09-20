[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)]
  [string] $VelocityHome,

  [Parameter(Mandatory = $true)]
  [string] $CandidateJar,

  [Parameter(Mandatory = $true)]
  [string] $ServiceIdentity,

  [string] $VelocityJar,

  [string] $JavaExecutable,

  [string] $PaperGlobalConfig,

  [string] $PaperServerProperties,

  [switch] $RequireBackend
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

$Checks = [System.Collections.Generic.List[object]]::new()

function Add-Check([string] $Name, [bool] $Passed, [string] $Detail) {
  $Status = if ($Passed) { "PASS" } else { "FAIL" }
  $Script:Checks.Add([pscustomobject]@{
    Name = $Name
    Status = $Status
    Detail = $Detail
  })
}

function Format-Path([string] $Path) {
  if ([string]::IsNullOrWhiteSpace($Path)) {
    return "unresolved"
  }
  return $Path.Replace("`r", "").Replace("`n", "")
}

function Resolve-FullPath([string] $Path, [string] $BasePath) {
  if ([string]::IsNullOrWhiteSpace($Path)) {
    return $null
  }
  try {
    if ([System.IO.Path]::IsPathRooted($Path)) {
      return [System.IO.Path]::GetFullPath($Path)
    }
    return [System.IO.Path]::GetFullPath((Join-Path $BasePath $Path))
  } catch {
    return $null
  }
}

function Resolve-Java([string] $Requested) {
  if (-not [string]::IsNullOrWhiteSpace($Requested)) {
    if (Test-Path -LiteralPath $Requested -PathType Leaf) {
      return [System.IO.Path]::GetFullPath($Requested)
    }
    $Command = Get-Command $Requested -CommandType Application -ErrorAction SilentlyContinue |
      Select-Object -First 1
    if ($null -ne $Command) {
      return $Command.Path
    }
    return $null
  }

  if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
    foreach ($Name in @("java.exe", "java")) {
      $FromHome = Join-Path $env:JAVA_HOME "bin\$Name"
      if (Test-Path -LiteralPath $FromHome -PathType Leaf) {
        return [System.IO.Path]::GetFullPath($FromHome)
      }
    }
  }

  foreach ($Name in @("java.exe", "java")) {
    $Command = Get-Command $Name -CommandType Application -ErrorAction SilentlyContinue |
      Select-Object -First 1
    if ($null -ne $Command) {
      return $Command.Path
    }
  }
  return $null
}

function Test-Java21([string] $JavaPath) {
  if ([string]::IsNullOrWhiteSpace($JavaPath)) {
    return $false
  }
  $PreviousErrorAction = $ErrorActionPreference
  try {
    $ErrorActionPreference = "Continue"
    $VersionOutput = & $JavaPath -version 2>&1 | Out-String
    $VersionExitCode = $LASTEXITCODE
  } catch {
    return $false
  } finally {
    $ErrorActionPreference = $PreviousErrorAction
  }
  if ($VersionExitCode -ne 0) {
    return $false
  }
  $Version = [regex]::Match($VersionOutput, 'version\s+"(?<major>\d+)')
  return $Version.Success -and $Version.Groups["major"].Value -eq "21"
}

function Test-RuntimeConfigSyntax(
  [string] $JavaPath,
  [string] $VelocityJarPath,
  [string] $StarxYamlPath,
  [string] $VelocityTomlPath,
  [string] $PaperYamlPath,
  [string] $PaperPropertiesPath
) {
  $Invalid = [pscustomobject]@{
    StarxYaml = $false
    VelocityToml = $false
    PaperYaml = $false
    PaperProperties = $false
    DuplicatePaperProperties = ""
  }
  if ([string]::IsNullOrWhiteSpace($JavaPath) -or
      [string]::IsNullOrWhiteSpace($VelocityJarPath)) {
    return $Invalid
  }

  $TempRoot = Join-Path ([System.IO.Path]::GetTempPath()) (
    "uworld-config-syntax-$PID-" + [guid]::NewGuid().ToString("N")
  )
  $SourcePath = Join-Path $TempRoot "UworldConfigSyntax.java"
  $InvocationExit = -1
  $CleanupSucceeded = $true
  try {
    [System.IO.Directory]::CreateDirectory($TempRoot) | Out-Null
    [System.IO.File]::WriteAllText(
      $SourcePath,
      @'
import com.electronwill.nightconfig.toml.TomlParser;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;
import org.yaml.snakeyaml.Yaml;

final class UworldConfigSyntax {
  private static final Set<String> PAPER_KEYS = Set.of(
      "online-mode", "server-port", "server-ip");

  private static final class CheckedProperties extends Properties {
    private final Set<String> seen = new LinkedHashSet<>();
    private final Set<String> duplicates = new LinkedHashSet<>();

    @Override
    public synchronized Object put(Object key, Object value) {
      if (key instanceof String text && PAPER_KEYS.contains(text)
          && !seen.add(text)) {
        duplicates.add(text);
      }
      return super.put(key, value);
    }
  }

  private static boolean yaml(String rawPath) {
    if ("-".equals(rawPath)) {
      return false;
    }
    Path path = Path.of(rawPath);
    if (!Files.isRegularFile(path)) {
      return false;
    }
    try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      new Yaml().load(reader);
      return true;
    } catch (Exception | LinkageError ignored) {
      return false;
    }
  }

  private static boolean toml(String rawPath) {
    if ("-".equals(rawPath)) {
      return false;
    }
    Path path = Path.of(rawPath);
    if (!Files.isRegularFile(path)) {
      return false;
    }
    try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      new TomlParser().parse(reader);
      return true;
    } catch (Exception | LinkageError ignored) {
      return false;
    }
  }

  private static CheckedProperties properties(String rawPath) {
    if ("-".equals(rawPath)) {
      return null;
    }
    Path path = Path.of(rawPath);
    if (!Files.isRegularFile(path)) {
      return null;
    }
    CheckedProperties properties = new CheckedProperties();
    try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      properties.load(reader);
      return properties;
    } catch (Exception | LinkageError ignored) {
      return null;
    }
  }

  public static void main(String[] args) {
    if (args.length != 4) {
      System.exit(64);
    }
    int failures = 0;
    if (!yaml(args[0])) {
      failures |= 1;
    }
    if (!toml(args[1])) {
      failures |= 2;
    }
    if (!yaml(args[2])) {
      failures |= 4;
    }
    CheckedProperties properties = properties(args[3]);
    if (properties == null || !properties.duplicates.isEmpty()) {
      failures |= 8;
    }
    if (properties != null && !properties.duplicates.isEmpty()) {
      System.out.println(
          "duplicate-paper-properties=" + String.join(",", properties.duplicates));
    }
    System.exit(32 | failures);
  }
}
'@,
      [System.Text.UTF8Encoding]::new($false)
    )
    $Arguments = @(
      "--class-path",
      $VelocityJarPath,
      $SourcePath,
      $(if ([string]::IsNullOrWhiteSpace($StarxYamlPath)) { "-" } else { $StarxYamlPath }),
      $(if ([string]::IsNullOrWhiteSpace($VelocityTomlPath)) { "-" } else { $VelocityTomlPath }),
      $(if ([string]::IsNullOrWhiteSpace($PaperYamlPath)) { "-" } else { $PaperYamlPath }),
      $(if ([string]::IsNullOrWhiteSpace($PaperPropertiesPath)) { "-" } else { $PaperPropertiesPath })
    )
    $InvocationOutput = ""
    $PreviousErrorAction = $ErrorActionPreference
    try {
      $ErrorActionPreference = "Continue"
      $InvocationOutput = (& $JavaPath @Arguments 2>&1) -join "`n"
      $InvocationExit = $LASTEXITCODE
    } catch {
      $InvocationExit = -1
    } finally {
      $ErrorActionPreference = $PreviousErrorAction
    }
  } catch {
    $InvocationExit = -1
  } finally {
    if ([System.IO.Directory]::Exists($TempRoot)) {
      try {
        [System.IO.Directory]::Delete($TempRoot, $true)
      } catch {
        $CleanupSucceeded = $false
      }
    }
  }

  if (-not $CleanupSucceeded -or $InvocationExit -lt 32 -or
      $InvocationExit -gt 47) {
    return $Invalid
  }
  $FailureMask = $InvocationExit -band 15
  $DuplicateMatch = [regex]::Match(
    $InvocationOutput,
    '(?m)^duplicate-paper-properties=(?<keys>[A-Za-z0-9,-]+)\s*$'
  )
  return [pscustomobject]@{
    StarxYaml = ($FailureMask -band 1) -eq 0
    VelocityToml = ($FailureMask -band 2) -eq 0
    PaperYaml = ($FailureMask -band 4) -eq 0
    PaperProperties = ($FailureMask -band 8) -eq 0
    DuplicatePaperProperties = if ($DuplicateMatch.Success) {
      $DuplicateMatch.Groups["keys"].Value
    } else {
      ""
    }
  }
}

function Get-PlainFile([string] $Path) {
  if ([string]::IsNullOrWhiteSpace($Path) -or
      -not (Test-Path -LiteralPath $Path -PathType Leaf)) {
    return $null
  }
  try {
    $Item = Get-Item -LiteralPath $Path -Force
    if (($Item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
      return $null
    }
    return $Item
  } catch {
    return $null
  }
}

function Resolve-VelocityJar([string] $Requested, [string] $VelocityRoot) {
  if (-not [string]::IsNullOrWhiteSpace($Requested)) {
    $RequestedPath = Resolve-FullPath $Requested (Get-Location).Path
    $RequestedItem = Get-PlainFile $RequestedPath
    if ($null -eq $RequestedItem) {
      return $null
    }
    try {
      $CanonicalJar = [System.IO.Path]::GetFullPath(
        $RequestedItem.FullName
      )
      $CanonicalRoot = [System.IO.Path]::GetFullPath(
        (Resolve-Path -LiteralPath $VelocityRoot).ProviderPath
      ).TrimEnd([System.IO.Path]::DirectorySeparatorChar)
      $JarParent = [System.IO.Path]::GetDirectoryName($CanonicalJar).TrimEnd(
        [System.IO.Path]::DirectorySeparatorChar
      )
      if (-not [string]::Equals(
          $JarParent,
          $CanonicalRoot,
          [System.StringComparison]::OrdinalIgnoreCase
        )) {
        return $null
      }
      return $CanonicalJar
    } catch {
      return $null
    }
  }

  $DefaultJar = Join-Path $VelocityRoot "velocity.jar"
  if (Test-Path -LiteralPath $DefaultJar -PathType Leaf) {
    $DefaultItem = Get-PlainFile $DefaultJar
    if ($null -eq $DefaultItem) {
      return $null
    }
    return [System.IO.Path]::GetFullPath($DefaultItem.FullName)
  }

  if (-not (Test-Path -LiteralPath $VelocityRoot -PathType Container)) {
    return $null
  }
  $Candidates = @(Get-ChildItem -LiteralPath $VelocityRoot -File -Filter "velocity-*.jar" `
    -ErrorAction SilentlyContinue)
  if ($Candidates.Count -ne 1) {
    return $null
  }
  $Candidate = Get-PlainFile $Candidates[0].FullName
  if ($null -eq $Candidate) {
    return $null
  }
  return $Candidate.FullName
}

function Get-VelocityBuild([string] $JarPath) {
  if ([string]::IsNullOrWhiteSpace($JarPath) -or
      -not (Test-Path -LiteralPath $JarPath -PathType Leaf)) {
    return $null
  }
  try {
    $Archive = [System.IO.Compression.ZipFile]::OpenRead($JarPath)
    try {
      $Entry = $Archive.GetEntry("META-INF/MANIFEST.MF")
      if ($null -eq $Entry) {
        return $null
      }
      $Reader = [System.IO.StreamReader]::new(
        $Entry.Open(),
        [System.Text.Encoding]::UTF8
      )
      try {
        $Manifest = $Reader.ReadToEnd()
      } finally {
        $Reader.Dispose()
      }
    } finally {
      $Archive.Dispose()
    }
  } catch {
    return $null
  }

  $Version = [regex]::Match(
    $Manifest,
    '(?m)^Implementation-Version:\s*3\.5\.0-SNAPSHOT\s+\([^\r\n)]*-b(?<build>\d+)\)\s*$'
  )
  if (-not $Version.Success) {
    return $null
  }
  return $Version.Groups["build"].Value
}

function Get-Sha256([string] $Path) {
  if ([string]::IsNullOrWhiteSpace($Path) -or
      -not (Test-Path -LiteralPath $Path -PathType Leaf)) {
    return $null
  }
  try {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToUpperInvariant()
  } catch {
    return $null
  }
}

function Read-PluginJarIdentity([System.IO.FileInfo] $Jar) {
  $DescriptorLimit = 1MB
  try {
    $Archive = [System.IO.Compression.ZipFile]::OpenRead($Jar.FullName)
    try {
      $Entries = @($Archive.Entries)
      $Descriptors = @($Entries | Where-Object {
        [string]::Equals(
          $_.FullName,
          "velocity-plugin.json",
          [System.StringComparison]::OrdinalIgnoreCase
        )
      })
      if ($Descriptors.Count -gt 1) {
        return $null
      }

      $PluginId = ""
      $MainClass = ""
      if ($Descriptors.Count -eq 1) {
        $DescriptorEntry = $Descriptors[0]
        if ($DescriptorEntry.Length -lt 1 -or
            $DescriptorEntry.Length -gt $DescriptorLimit) {
          return $null
        }
        $Reader = [System.IO.StreamReader]::new(
          $DescriptorEntry.Open(),
          [System.Text.Encoding]::UTF8,
          $true
        )
        try {
          $DescriptorText = $Reader.ReadToEnd()
        } finally {
          $Reader.Dispose()
        }
        $Descriptor = $DescriptorText | ConvertFrom-Json -ErrorAction Stop
        $IdProperty = $Descriptor.PSObject.Properties["id"]
        $MainProperty = $Descriptor.PSObject.Properties["main"]
        if ($null -eq $IdProperty -or $null -eq $MainProperty -or
            -not ($IdProperty.Value -is [string]) -or
            -not ($MainProperty.Value -is [string])) {
          return $null
        }
        $PluginId = $IdProperty.Value.Trim()
        $MainClass = $MainProperty.Value.Trim()
        if ([string]::IsNullOrWhiteSpace($PluginId) -or
            [string]::IsNullOrWhiteSpace($MainClass)) {
          return $null
        }
      }

      $HasLimboClass = @($Entries | Where-Object {
        $_.FullName -match '(?i)^net/elytrium/limboapi/.+\.class$'
      }).Count -gt 0
      $HasNestedLimboJar = @($Entries | Where-Object {
        $_.FullName -match '(?i)(?:^|/)limbo[-_.]?api[^/]*\.jar$'
      }).Count -gt 0
      $IsStarx = $PluginId -ieq "starx" -and
        $MainClass -ceq "io.github.addxiaoyi.starx.velocity.StarxVelocityPlugin"
      $IsExternalLimbo = $PluginId -ieq "limboapi" -or
        $MainClass.StartsWith(
          "net.elytrium.limboapi.",
          [System.StringComparison]::OrdinalIgnoreCase
        ) -or $HasLimboClass -or $HasNestedLimboJar
      return [pscustomobject]@{
        Jar = $Jar
        IsStarx = $IsStarx
        IsExternalLimbo = $IsExternalLimbo
      }
    } finally {
      $Archive.Dispose()
    }
  } catch {
    return $null
  }
}

function Read-ConfigScalar([string] $Raw) {
  $Builder = [System.Text.StringBuilder]::new()
  $SingleQuoted = $false
  $DoubleQuoted = $false
  $Escaped = $false
  $SquareDepth = 0
  $CurlyDepth = 0
  for ($Index = 0; $Index -lt $Raw.Length; $Index++) {
    $Character = $Raw[$Index]
    if ($Escaped) {
      $Builder.Append($Character) | Out-Null
      $Escaped = $false
      continue
    }
    if ($DoubleQuoted -and $Character -eq '\') {
      $Builder.Append($Character) | Out-Null
      $Escaped = $true
      continue
    }
    if (-not $SingleQuoted -and $Character -eq '"') {
      $CanStartQuote = $Builder.ToString().Trim().Length -eq 0 -or
        $SquareDepth -gt 0 -or $CurlyDepth -gt 0
      if ($DoubleQuoted -or $CanStartQuote) {
        $DoubleQuoted = -not $DoubleQuoted
        $Builder.Append($Character) | Out-Null
        continue
      }
    }
    if (-not $DoubleQuoted -and $Character -eq "'") {
      if ($SingleQuoted -and $Index + 1 -lt $Raw.Length -and
          $Raw[$Index + 1] -eq "'") {
        $Builder.Append("''") | Out-Null
        $Index++
        continue
      }
      $CanStartQuote = $Builder.ToString().Trim().Length -eq 0 -or
        $SquareDepth -gt 0 -or $CurlyDepth -gt 0
      if ($SingleQuoted -or $CanStartQuote) {
        $SingleQuoted = -not $SingleQuoted
        $Builder.Append($Character) | Out-Null
        continue
      }
    }
    if (-not $SingleQuoted -and -not $DoubleQuoted) {
      if ($Character -eq '#' -and
          ($Index -eq 0 -or [char]::IsWhiteSpace($Raw[$Index - 1]))) {
        break
      }
      if ($Character -eq '[') {
        $SquareDepth++
      } elseif ($Character -eq ']') {
        $SquareDepth--
      } elseif ($Character -eq '{') {
        $CurlyDepth++
      } elseif ($Character -eq '}') {
        $CurlyDepth--
      }
      if ($SquareDepth -lt 0 -or $CurlyDepth -lt 0) {
        return [pscustomobject]@{ Valid = $false; Value = ""; Block = $false }
      }
    }
    $Builder.Append($Character) | Out-Null
  }
  if ($Escaped -or $SingleQuoted -or $DoubleQuoted -or
      $SquareDepth -ne 0 -or $CurlyDepth -ne 0) {
    return [pscustomobject]@{ Valid = $false; Value = ""; Block = $false }
  }
  $Value = $Builder.ToString().Trim()
  if ($Value.StartsWith('"') -and
      $Value -notmatch '^"(?:\\.|[^"])*"$') {
    return [pscustomobject]@{ Valid = $false; Value = ""; Block = $false }
  }
  if ($Value.StartsWith("'") -and
      $Value -notmatch "^'(?:''|[^'])*'$" ) {
    return [pscustomobject]@{ Valid = $false; Value = ""; Block = $false }
  }
  $Block = $Value -match '^[|>](?:[1-9][+-]?|[+-][1-9]?)?$'
  return [pscustomobject]@{ Valid = $true; Value = $Value; Block = $Block }
}

function Convert-YamlScalar([string] $Value) {
  if ($Value.Length -ge 2 -and $Value.StartsWith('"') -and $Value.EndsWith('"')) {
    return $Value.Substring(1, $Value.Length - 2).Replace('\"', '"').Replace('\\', '\')
  }
  if ($Value.Length -ge 2 -and $Value.StartsWith("'") -and $Value.EndsWith("'")) {
    return $Value.Substring(1, $Value.Length - 2).Replace("''", "'")
  }
  return $Value
}

function Read-YamlDocument([string] $Path) {
  if ([string]::IsNullOrWhiteSpace($Path) -or
      -not (Test-Path -LiteralPath $Path -PathType Leaf)) {
    return $null
  }
  $Scalars = @{}
  $Mappings = @{}
  $Parents = [System.Collections.Generic.List[object]]::new()
  $SeenKeys = [System.Collections.Generic.HashSet[string]]::new(
    [System.StringComparer]::Ordinal
  )
  $BlockIndent = $null
  $ScalarIndent = $null
  $PlainContinuation = $false
  $LineNumber = 0
  try {
    foreach ($Line in [System.IO.File]::ReadAllLines($Path)) {
      $LineNumber++
      if ([string]::IsNullOrWhiteSpace($Line) -or
          $Line.TrimStart().StartsWith("#")) {
        continue
      }
      $Indent = [regex]::Match($Line, '^ *').Value.Length
      if ($null -ne $BlockIndent) {
        if ($Indent -gt $BlockIndent) {
          continue
        }
        $BlockIndent = $null
      }
      if ($Line.Substring(0, $Indent) -match "`t" -or
          ($Indent -eq 0 -and $Line.StartsWith("`t"))) {
        return $null
      }
      if ($null -ne $ScalarIndent) {
        if ($Indent -gt $ScalarIndent) {
          $Continuation = $Line.Trim()
          $LooksStructured = $Continuation -match '^[A-Za-z0-9_.-]+(?::[A-Za-z0-9_./-]+)*:(?:\s|$)' -or
            $Continuation -match '^-\s'
          $ParsedContinuation = Read-ConfigScalar $Continuation
          if (-not $PlainContinuation -or $LooksStructured -or
              -not $ParsedContinuation.Valid -or
              [string]::IsNullOrWhiteSpace($ParsedContinuation.Value) -or
              $ParsedContinuation.Block) {
            return $null
          }
          continue
        }
        $ScalarIndent = $null
        $PlainContinuation = $false
      }
      while ($Parents.Count -gt 0 -and
          $Parents[$Parents.Count - 1].Indent -ge $Indent) {
        $Parents.RemoveAt($Parents.Count - 1)
      }
      if ($Indent -gt 0 -and $Parents.Count -eq 0) {
        return $null
      }

      $Match = [regex]::Match(
        $Line,
        '^(?<indent> *)(?<key>[A-Za-z0-9_.-]+(?::[A-Za-z0-9_./-]+)*):(?<tail>(?:\s.*)?)$'
      )
      if ($Match.Success) {
        $ParentScope = if ($Parents.Count -eq 0) {
          "root"
        } else {
          $Parents[$Parents.Count - 1].Scope
        }
        $Key = $Match.Groups["key"].Value
        if (-not $SeenKeys.Add("$ParentScope|$Key")) {
          return $null
        }
        $InSequence = @($Parents | Where-Object { $_.Sequence }).Count -gt 0
        $Segments = [System.Collections.Generic.List[string]]::new()
        foreach ($Parent in $Parents) {
          if (-not [string]::IsNullOrWhiteSpace($Parent.PathKey)) {
            $Segments.Add($Parent.PathKey)
          }
        }
        $Segments.Add($Key)
        $YamlPath = $Segments -join "."
        $Tail = $Match.Groups["tail"].Value
        if ([string]::IsNullOrWhiteSpace($Tail)) {
          $Parents.Add([pscustomobject]@{
            Indent = $Indent
            Scope = "map:$LineNumber"
            PathKey = if ($InSequence) { $null } else { $Key }
            Sequence = $InSequence
          })
          if (-not $InSequence) {
            $Mappings[$YamlPath] = $true
          }
          continue
        }
        $Parsed = Read-ConfigScalar $Tail
        if (-not $Parsed.Valid -or [string]::IsNullOrWhiteSpace($Parsed.Value)) {
          return $null
        }
        if ($Parsed.Block) {
          $BlockIndent = $Indent
          if (-not $InSequence) {
            $Scalars[$YamlPath] = ""
          }
          continue
        }
        $ScalarIndent = $Indent
        $PlainContinuation = -not (
          $Parsed.Value.StartsWith('"') -or
          $Parsed.Value.StartsWith("'") -or
          $Parsed.Value.StartsWith("[") -or
          $Parsed.Value.StartsWith("{")
        )
        if (-not $InSequence) {
          $Scalars[$YamlPath] = Convert-YamlScalar $Parsed.Value
        }
        continue
      }

      $SequenceMatch = [regex]::Match(
        $Line,
        '^(?<indent> *)-\s*(?<tail>.*)$'
      )
      if ($SequenceMatch.Success) {
        $Tail = $SequenceMatch.Groups["tail"].Value
        $ItemScope = "item:$LineNumber"
        if ([string]::IsNullOrWhiteSpace($Tail)) {
          $Parents.Add([pscustomobject]@{
            Indent = $Indent
            Scope = $ItemScope
            PathKey = $null
            Sequence = $true
          })
          continue
        }
        $InlineMapping = [regex]::Match(
          $Tail,
          '^(?<key>[A-Za-z0-9_.-]+(?::[A-Za-z0-9_./-]+)*):(?<value>(?:\s.*)?)$'
        )
        if ($InlineMapping.Success) {
          $Parents.Add([pscustomobject]@{
            Indent = $Indent
            Scope = $ItemScope
            PathKey = $null
            Sequence = $true
          })
          $InlineKey = $InlineMapping.Groups["key"].Value
          if (-not $SeenKeys.Add("$ItemScope|$InlineKey")) {
            return $null
          }
          $InlineTail = $InlineMapping.Groups["value"].Value
          if ([string]::IsNullOrWhiteSpace($InlineTail)) {
            $Parents.Add([pscustomobject]@{
              Indent = $Indent
              Scope = "map:$LineNumber"
              PathKey = $null
              Sequence = $true
            })
            continue
          }
          $Parsed = Read-ConfigScalar $InlineTail
          if (-not $Parsed.Valid -or [string]::IsNullOrWhiteSpace($Parsed.Value)) {
            return $null
          }
          if ($Parsed.Block) {
            $BlockIndent = $Indent
          }
          continue
        }
        $Parsed = Read-ConfigScalar $Tail
        if (-not $Parsed.Valid -or [string]::IsNullOrWhiteSpace($Parsed.Value)) {
          return $null
        }
        if ($Parsed.Block) {
          $BlockIndent = $Indent
        }
        $ScalarIndent = $Indent
        $PlainContinuation = -not (
          $Parsed.Value.StartsWith('"') -or
          $Parsed.Value.StartsWith("'") -or
          $Parsed.Value.StartsWith("[") -or
          $Parsed.Value.StartsWith("{")
        )
        continue
      }

      if ($Line.Trim() -in @("---", "...")) {
        continue
      }
      return $null
    }
  } catch {
    return $null
  }
  return [pscustomobject]@{
    Scalars = $Scalars
    Mappings = $Mappings
  }
}

function Get-StarxConfigFragmentPaths([string] $IndexPath) {
  if ([string]::IsNullOrWhiteSpace($IndexPath) -or
      -not (Test-Path -LiteralPath $IndexPath -PathType Leaf)) {
    return @()
  }

  try {
    $IndexFullPath = [System.IO.Path]::GetFullPath($IndexPath)
    $Parent = [System.IO.Path]::GetDirectoryName($IndexFullPath)
    $IndexDocument = Read-YamlDocument $IndexFullPath
    if ($null -eq $IndexDocument -or
        -not $IndexDocument.Mappings.ContainsKey("config-files")) {
      return @()
    }

    $DirectoryName = "config"
    if ($IndexDocument.Scalars.ContainsKey("config-files.directory")) {
      $DirectoryName = [string] $IndexDocument.Scalars["config-files.directory"]
    }
    if ([string]::IsNullOrWhiteSpace($DirectoryName) -or
        [System.IO.Path]::IsPathRooted($DirectoryName)) {
      return @()
    }
    $Directory = [System.IO.Path]::GetFullPath((Join-Path $Parent $DirectoryName))
    $ParentPrefix = $Parent.TrimEnd('\') + '\'
    if ($Directory -ne $Parent -and
        -not $Directory.StartsWith(
          $ParentPrefix,
          [System.StringComparison]::OrdinalIgnoreCase
        )) {
      return @()
    }

    $Files = [System.Collections.Generic.List[string]]::new()
    $InFiles = $false
    $FilesIndent = -1
    foreach ($Line in [System.IO.File]::ReadAllLines($IndexFullPath)) {
      if ([string]::IsNullOrWhiteSpace($Line) -or
          $Line.TrimStart().StartsWith("#")) {
        continue
      }
      $FilesMatch = [regex]::Match(
        $Line,
        '^(?<indent> *)files:\s*(?<tail>.*)$'
      )
      if ($FilesMatch.Success) {
        $FilesIndent = $FilesMatch.Groups["indent"].Value.Length
        $Tail = $FilesMatch.Groups["tail"].Value.Trim()
        $InFiles = $true
        if ($Tail.Length -gt 0) {
          if (-not ($Tail.StartsWith("[") -and $Tail.EndsWith("]"))) {
            return @()
          }
          foreach ($Item in $Tail.Substring(1, $Tail.Length - 2).Split(',')) {
            $Name = $Item.Trim().Trim('"').Trim("'")
            if ($Name.Length -gt 0) {
              $Files.Add($Name)
            }
          }
          $InFiles = $false
        }
        continue
      }
      if (-not $InFiles) {
        continue
      }
      $Indent = [regex]::Match($Line, '^ *').Value.Length
      if ($Indent -le $FilesIndent) {
        $InFiles = $false
        continue
      }
      $ItemMatch = [regex]::Match(
        $Line,
        '^\s*-\s*(?<name>[A-Za-z0-9_.-]+\.(?:yml|yaml))\s*(?:#.*)?$'
      )
      if (-not $ItemMatch.Success) {
        return @()
      }
      $Files.Add($ItemMatch.Groups["name"].Value)
    }

    if ($Files.Count -eq 0) {
      return @()
    }
    $Seen = [System.Collections.Generic.HashSet[string]]::new(
      [System.StringComparer]::OrdinalIgnoreCase
    )
    $Paths = [System.Collections.Generic.List[string]]::new()
    foreach ($Name in $Files) {
      $NamePath = [System.IO.Path]::GetFileName($Name)
      if ($NamePath -cne $Name -or
          [System.IO.Path]::GetExtension($Name).ToLowerInvariant() -notin @('.yml', '.yaml') -or
          -not $Seen.Add($Name)) {
        return @()
      }
      $Fragment = [System.IO.Path]::GetFullPath((Join-Path $Directory $Name))
      if (-not $Fragment.StartsWith($ParentPrefix, [System.StringComparison]::OrdinalIgnoreCase) -or
          -not (Test-Path -LiteralPath $Fragment -PathType Leaf)) {
        return @()
      }
      $Paths.Add($Fragment)
    }
    return @($Paths)
  } catch {
    return @()
  }
}

function Read-StarxConfigDocument([string] $Path) {
  $Index = Read-YamlDocument $Path
  if ($null -eq $Index) {
    return $null
  }
  if (-not $Index.Mappings.ContainsKey("config-files")) {
    return $Index
  }

  $FragmentPaths = @(Get-StarxConfigFragmentPaths $Path)
  if ($FragmentPaths.Count -eq 0) {
    return $null
  }
  $Scalars = @{}
  $Mappings = @{}
  foreach ($Document in @($Index) + @($FragmentPaths | ForEach-Object {
      Read-YamlDocument $_
    })) {
    if ($null -eq $Document) {
      return $null
    }
    foreach ($Key in $Document.Scalars.Keys) {
      if (-not $Key.StartsWith("config-files.", [System.StringComparison]::Ordinal)) {
        $Scalars[$Key] = $Document.Scalars[$Key]
      }
    }
    foreach ($Key in $Document.Mappings.Keys) {
      if (-not $Key.StartsWith("config-files", [System.StringComparison]::Ordinal)) {
        $Mappings[$Key] = $true
      }
    }
  }
  return [pscustomobject]@{
    Scalars = $Scalars
    Mappings = $Mappings
  }
}

function Test-TomlValue([string] $Value) {
  if ([string]::IsNullOrWhiteSpace($Value)) {
    return $false
  }
  if ($Value -match '^"(?:\\.|[^"])*"$' -or
      $Value -match "^'[^']*'$" -or
      $Value -match '^(?i:true|false)$' -or
      $Value -match '^[+-]?(?:\d[\d_]*)(?:\.\d[\d_]*)?(?:[eE][+-]?\d[\d_]*)?$' -or
      $Value -match '^[+-]?(?i:inf|nan)$' -or
      $Value -match '^\d{4}-\d{2}-\d{2}(?:[Tt ].*)?$') {
    return $true
  }
  if (($Value.StartsWith("[") -and $Value.EndsWith("]")) -or
      ($Value.StartsWith("{") -and $Value.EndsWith("}"))) {
    return $true
  }
  return $false
}

function Convert-TomlString([string] $Value) {
  if ($Value -match '^"(?:\\.|[^"])*"$') {
    return $Value.Substring(1, $Value.Length - 2).Replace('\"', '"').Replace('\\', '\')
  }
  if ($Value -match "^'[^']*'$") {
    return $Value.Substring(1, $Value.Length - 2)
  }
  return $null
}

function Read-VelocityConfig([string] $Path) {
  if ([string]::IsNullOrWhiteSpace($Path) -or
      -not (Test-Path -LiteralPath $Path -PathType Leaf)) {
    return $null
  }
  $Servers = @{}
  $Section = ""
  $OnlineMode = ""
  $ForwardingMode = ""
  $SecretFile = ""
  $SeenTables = [System.Collections.Generic.HashSet[string]]::new(
    [System.StringComparer]::Ordinal
  )
  $SeenKeys = [System.Collections.Generic.HashSet[string]]::new(
    [System.StringComparer]::Ordinal
  )
  $ArrayTableCounts = @{}
  try {
    foreach ($SourceLine in [System.IO.File]::ReadAllLines($Path)) {
      if ([string]::IsNullOrWhiteSpace($SourceLine) -or
          $SourceLine.TrimStart().StartsWith("#")) {
        continue
      }
      $ParsedLine = Read-ConfigScalar $SourceLine
      if (-not $ParsedLine.Valid -or [string]::IsNullOrWhiteSpace($ParsedLine.Value)) {
        return $null
      }
      $Line = $ParsedLine.Value
      $ArraySectionMatch = [regex]::Match(
        $Line,
        '^\[\[(?<section>[A-Za-z0-9_.-]+)\]\]$'
      )
      if ($ArraySectionMatch.Success) {
        $ArrayName = $ArraySectionMatch.Groups["section"].Value
        $ArrayIndex = if ($ArrayTableCounts.ContainsKey($ArrayName)) {
          [int] $ArrayTableCounts[$ArrayName] + 1
        } else {
          1
        }
        $ArrayTableCounts[$ArrayName] = $ArrayIndex
        $Section = "$ArrayName#$ArrayIndex"
        continue
      }
      $SectionMatch = [regex]::Match($Line, '^\[(?<section>[A-Za-z0-9_.-]+)\]$')
      if ($SectionMatch.Success) {
        $Section = $SectionMatch.Groups["section"].Value
        if (-not $SeenTables.Add($Section)) {
          return $null
        }
        continue
      }
      $ValueMatch = [regex]::Match(
        $Line,
        '^(?<key>[A-Za-z0-9_.-]+)\s*=\s*(?<value>.+)$'
      )
      if (-not $ValueMatch.Success) {
        return $null
      }
      $Key = $ValueMatch.Groups["key"].Value
      $Value = $ValueMatch.Groups["value"].Value.Trim()
      if (-not (Test-TomlValue $Value) -or
          -not $SeenKeys.Add("$Section|$Key")) {
        return $null
      }
      $StringValue = Convert-TomlString $Value
      if ($Section -eq "servers") {
        if ($null -ne $StringValue) {
          $Servers[$Key] = $StringValue
        }
      } elseif ($Section.Length -eq 0 -and
          $Key -eq "online-mode" -and
          $Value -match '^(?i:true|false)$') {
        $OnlineMode = $Value.ToLowerInvariant()
      } elseif ($Section.Length -eq 0 -and
          $Key -eq "player-info-forwarding-mode" -and
          $null -ne $StringValue) {
        $ForwardingMode = $StringValue
      } elseif ($Section.Length -eq 0 -and
          $Key -eq "forwarding-secret-file" -and
          $null -ne $StringValue) {
        $SecretFile = $StringValue
      }
    }
  } catch {
    return $null
  }
  return [pscustomobject]@{
    Servers = $Servers
    OnlineMode = $OnlineMode
    ForwardingMode = $ForwardingMode
    SecretFile = $SecretFile
  }
}

function Convert-ServerEndpoint([string] $Address) {
  if ([string]::IsNullOrWhiteSpace($Address)) {
    return $null
  }
  $Match = [regex]::Match(
    $Address.Trim(),
    '^(?:\[(?<ipv6>[^\]]+)\]|(?<host>[^:]+)):(?<port>\d+)$'
  )
  if (-not $Match.Success) {
    return $null
  }
  $Port = 0
  if (-not [int]::TryParse($Match.Groups["port"].Value, [ref] $Port) -or
      $Port -lt 1 -or $Port -gt 65535) {
    return $null
  }
  $HostName = if ($Match.Groups["ipv6"].Success) {
    $Match.Groups["ipv6"].Value
  } else {
    $Match.Groups["host"].Value
  }
  if ([string]::IsNullOrWhiteSpace($HostName)) {
    return $null
  }
  return [pscustomobject]@{
    HostName = $HostName
    Port = $Port
  }
}

function Test-TcpEndpoint([string] $HostName, [int] $Port) {
  $Client = [System.Net.Sockets.TcpClient]::new()
  $AsyncResult = $null
  try {
    $AsyncResult = $Client.BeginConnect($HostName, $Port, $null, $null)
    if (-not $AsyncResult.AsyncWaitHandle.WaitOne(1000, $false)) {
      return $false
    }
    $Client.EndConnect($AsyncResult)
    return $Client.Connected
  } catch {
    return $false
  } finally {
    if ($null -ne $AsyncResult) {
      $AsyncResult.AsyncWaitHandle.Close()
    }
    $Client.Dispose()
  }
}

function Read-SecretFile([string] $Path) {
  if ([string]::IsNullOrWhiteSpace($Path) -or
      -not (Test-Path -LiteralPath $Path -PathType Leaf)) {
    return $null
  }
  try {
    $Secret = [System.IO.File]::ReadAllText($Path).Trim()
    if ([string]::IsNullOrWhiteSpace($Secret)) {
      return $null
    }
    return $Secret
  } catch {
    return $null
  }
}

function Read-Property([string] $Path, [string] $Key) {
  if ([string]::IsNullOrWhiteSpace($Path) -or
      -not (Test-Path -LiteralPath $Path -PathType Leaf)) {
    return $null
  }
  try {
    foreach ($Line in [System.IO.File]::ReadAllLines($Path)) {
      $Trimmed = $Line.Trim()
      if ($Trimmed.Length -eq 0 -or
          $Trimmed.StartsWith("#") -or
          $Trimmed.StartsWith("!")) {
        continue
      }
      $Match = [regex]::Match(
        $Trimmed,
        '^(?<key>[^:=\s]+)\s*[:=]\s*(?<value>.*)$'
      )
      if ($Match.Success -and $Match.Groups["key"].Value -eq $Key) {
        return $Match.Groups["value"].Value.Trim()
      }
    }
  } catch {
    return $null
  }
  return $null
}

function Convert-IpAddress([string] $Value) {
  if ([string]::IsNullOrWhiteSpace($Value)) {
    return $null
  }
  [System.Net.IPAddress] $Address = $null
  if (-not [System.Net.IPAddress]::TryParse($Value.Trim(), [ref] $Address)) {
    return $null
  }
  if ($Address.IsIPv4MappedToIPv6) {
    $Address = $Address.MapToIPv4()
  }
  return $Address
}

function Test-PrivateServerAddress([string] $Value) {
  $Address = Convert-IpAddress $Value
  if ($null -eq $Address) {
    return $false
  }
  if ([System.Net.IPAddress]::IsLoopback($Address)) {
    return $true
  }
  $Bytes = $Address.GetAddressBytes()
  if ($Address.AddressFamily -eq [System.Net.Sockets.AddressFamily]::InterNetwork) {
    return $Bytes[0] -eq 10 -or
      ($Bytes[0] -eq 172 -and $Bytes[1] -ge 16 -and $Bytes[1] -le 31) -or
      ($Bytes[0] -eq 192 -and $Bytes[1] -eq 168)
  }
  if ($Address.AddressFamily -eq [System.Net.Sockets.AddressFamily]::InterNetworkV6) {
    return ($Bytes[0] -band 0xFE) -eq 0xFC
  }
  return $false
}

function Test-ServerTargetIdentity(
  [string] $TargetHost,
  [string] $PaperServerIp
) {
  $PaperAddress = Convert-IpAddress $PaperServerIp
  if ($null -eq $PaperAddress -or
      -not (Test-PrivateServerAddress $PaperServerIp) -or
      [string]::IsNullOrWhiteSpace($TargetHost)) {
    return $false
  }

  $TargetAddress = Convert-IpAddress $TargetHost
  if ($null -ne $TargetAddress) {
    return $PaperAddress.Equals($TargetAddress)
  }

  try {
    $ResolvedAddresses = [System.Net.Dns]::GetHostAddresses($TargetHost.Trim())
  } catch {
    return $false
  }
  $UniqueAddresses = [System.Collections.Generic.HashSet[string]]::new(
    [System.StringComparer]::Ordinal
  )
  foreach ($ResolvedAddress in $ResolvedAddresses) {
    $NormalizedAddress = if ($ResolvedAddress.IsIPv4MappedToIPv6) {
      $ResolvedAddress.MapToIPv4()
    } else {
      $ResolvedAddress
    }
    $UniqueAddresses.Add($NormalizedAddress.ToString()) | Out-Null
  }
  return $UniqueAddresses.Count -eq 1 -and
    $UniqueAddresses.Contains($PaperAddress.ToString())
}

function Resolve-SqliteParent(
  [string] $DatabaseValue,
  [string] $VelocityRoot
) {
  if ([string]::IsNullOrWhiteSpace($DatabaseValue)) {
    return $null
  }
  $DatabasePath = $DatabaseValue.Trim()
  if ($DatabasePath.StartsWith("jdbc:sqlite:", [System.StringComparison]::OrdinalIgnoreCase)) {
    $DatabasePath = $DatabasePath.Substring("jdbc:sqlite:".Length)
  }
  if ($DatabasePath -eq ":memory:") {
    return $null
  }
  $Resolved = Resolve-FullPath $DatabasePath $VelocityRoot
  if ([string]::IsNullOrWhiteSpace($Resolved)) {
    return $null
  }
  return [System.IO.Path]::GetDirectoryName($Resolved)
}

function Test-DirectoryWritable([string] $Path) {
  if ([string]::IsNullOrWhiteSpace($Path) -or
      -not (Test-Path -LiteralPath $Path -PathType Container)) {
    return $false
  }
  $Probe = Join-Path $Path (
    ".uworld-write-probe-$PID-" + [guid]::NewGuid().ToString("N") + ".tmp"
  )
  $MovedProbe = "$Probe.moved"
  $Stream = $null
  $Succeeded = $false
  try {
    $Stream = [System.IO.FileStream]::new(
      $Probe,
      [System.IO.FileMode]::CreateNew,
      [System.IO.FileAccess]::Write,
      [System.IO.FileShare]::None,
      1,
      [System.IO.FileOptions]::None
    )
    $Stream.WriteByte(0)
    $Stream.Flush($true)
    $Stream.Dispose()
    $Stream = $null
    [System.IO.File]::Move($Probe, $MovedProbe)
    [System.IO.File]::Delete($MovedProbe)
    $Succeeded = $true
  } catch {
    return $false
  } finally {
    if ($null -ne $Stream) {
      $Stream.Dispose()
    }
    if (Test-Path -LiteralPath $Probe -PathType Leaf) {
      try {
        [System.IO.File]::Delete($Probe)
      } catch {
        # A failed cleanup makes the permission probe fail below.
      }
    }
    if (Test-Path -LiteralPath $MovedProbe -PathType Leaf) {
      try {
        [System.IO.File]::Delete($MovedProbe)
      } catch {
        # A failed cleanup makes the permission probe fail below.
      }
    }
  }
  return $Succeeded -and
    -not (Test-Path -LiteralPath $Probe -PathType Leaf) -and
    -not (Test-Path -LiteralPath $MovedProbe -PathType Leaf)
}

function Resolve-WindowsSid([string] $Identity) {
  if ([string]::IsNullOrWhiteSpace($Identity)) {
    return $null
  }
  try {
    $Account = [System.Security.Principal.NTAccount]::new($Identity.Trim())
    return $Account.Translate([System.Security.Principal.SecurityIdentifier])
  } catch {
    return $null
  }
}

function Test-WindowsDirectoryAcl(
  [string] $Path,
  [System.Security.Principal.SecurityIdentifier] $ServiceSid
) {
  if ($null -eq $ServiceSid -or
      -not (Test-Path -LiteralPath $Path -PathType Container)) {
    return $false
  }
  try {
    $Acl = Get-Acl -LiteralPath $Path
    $Owner = Resolve-WindowsSid $Acl.Owner
    if ($null -eq $Owner -or $Owner.Value -cne $ServiceSid.Value) {
      return $false
    }
    $Rules = $Acl.GetAccessRules(
      $true,
      $true,
      [System.Security.Principal.SecurityIdentifier]
    )
  } catch {
    return $false
  }

  $Required = [int64] [System.Security.AccessControl.FileSystemRights]::Modify
  $Allowed = [int64] 0
  $Denied = [int64] 0
  foreach ($Rule in $Rules) {
    if ($Rule.IdentityReference.Value -cne $ServiceSid.Value -or
        ($Rule.PropagationFlags -band
          [System.Security.AccessControl.PropagationFlags]::InheritOnly)) {
      continue
    }
    $Rights = [int64] $Rule.FileSystemRights
    if ($Rule.AccessControlType -eq
        [System.Security.AccessControl.AccessControlType]::Deny) {
      $Denied = $Denied -bor $Rights
    } else {
      $Allowed = $Allowed -bor $Rights
    }
  }
  return ($Denied -band $Required) -eq 0 -and
    ($Allowed -band $Required) -eq $Required
}

function Invoke-QuietCommand(
  [string] $Executable,
  [string[]] $Arguments
) {
  $PreviousErrorAction = $ErrorActionPreference
  try {
    $ErrorActionPreference = "Continue"
    & $Executable @Arguments *> $null
    return $LASTEXITCODE -eq 0
  } catch {
    return $false
  } finally {
    $ErrorActionPreference = $PreviousErrorAction
  }
}

function Test-UnixDirectoryAsIdentity(
  [string] $Path,
  [string] $Identity
) {
  if ([string]::IsNullOrWhiteSpace($Identity) -or
      $Identity -notmatch '^[a-z_][a-z0-9_-]*[$]?$' -or
      -not (Test-Path -LiteralPath $Path -PathType Container)) {
    return $false
  }
  $RunUser = Get-Command runuser -CommandType Application -ErrorAction SilentlyContinue |
    Select-Object -First 1
  $Touch = Get-Command touch -CommandType Application -ErrorAction SilentlyContinue |
    Select-Object -First 1
  $Move = Get-Command mv -CommandType Application -ErrorAction SilentlyContinue |
    Select-Object -First 1
  $Remove = Get-Command rm -CommandType Application -ErrorAction SilentlyContinue |
    Select-Object -First 1
  if ($null -in @($RunUser, $Touch, $Move, $Remove)) {
    return $false
  }

  $Probe = Join-Path $Path (
    ".uworld-write-probe-$PID-" + [guid]::NewGuid().ToString("N") + ".tmp"
  )
  $MovedProbe = "$Probe.moved"
  try {
    if (-not (Invoke-QuietCommand $RunUser.Path @(
          '--user', $Identity, '--', $Touch.Path, '--', $Probe))) {
      return $false
    }
    if (-not (Invoke-QuietCommand $RunUser.Path @(
          '--user', $Identity, '--', $Move.Path, '--', $Probe, $MovedProbe))) {
      return $false
    }
    return Invoke-QuietCommand $RunUser.Path @(
      '--user', $Identity, '--', $Remove.Path, '--', $MovedProbe)
  } finally {
    foreach ($Leftover in @($Probe, $MovedProbe)) {
      if (Test-Path -LiteralPath $Leftover -PathType Leaf) {
        Remove-Item -LiteralPath $Leftover -Force -ErrorAction SilentlyContinue
      }
    }
  }
}

function Test-DirectoryWritableAsIdentity(
  [string] $Path,
  [string] $Identity
) {
  $Failed = [pscustomobject]@{
    Passed = $false
    Method = "identity-unresolved"
  }
  if ([string]::IsNullOrWhiteSpace($Identity)) {
    return $Failed
  }

  $IsWindowsPlatform = [System.Runtime.InteropServices.RuntimeInformation]::IsOSPlatform(
    [System.Runtime.InteropServices.OSPlatform]::Windows
  )
  if ($IsWindowsPlatform) {
    $ServiceSid = Resolve-WindowsSid $Identity
    if ($null -eq $ServiceSid) {
      return $Failed
    }
    $CurrentSid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User
    if ($CurrentSid.Value -ceq $ServiceSid.Value) {
      return [pscustomobject]@{
        Passed = Test-DirectoryWritable $Path
        Method = "live-probe"
      }
    }
    return [pscustomobject]@{
      Passed = Test-WindowsDirectoryAcl $Path $ServiceSid
      Method = "acl-owner"
    }
  }

  $Id = Get-Command id -CommandType Application -ErrorAction SilentlyContinue |
    Select-Object -First 1
  if ($null -eq $Id -or
      -not (Invoke-QuietCommand $Id.Path @('-u', '--', $Identity))) {
    return $Failed
  }
  $CurrentIdentity = (& $Id.Path '-un' 2>$null | Select-Object -First 1)
  if ($LASTEXITCODE -eq 0 -and $CurrentIdentity -ceq $Identity) {
    return [pscustomobject]@{
      Passed = Test-DirectoryWritable $Path
      Method = "live-probe"
    }
  }
  $CurrentUid = (& $Id.Path '-u' 2>$null | Select-Object -First 1)
  if ($LASTEXITCODE -ne 0 -or $CurrentUid -cne '0') {
    return [pscustomobject]@{
      Passed = $false
      Method = "identity-mismatch"
    }
  }
  return [pscustomobject]@{
    Passed = Test-UnixDirectoryAsIdentity $Path $Identity
    Method = "runuser-probe"
  }
}

try {
  $VelocityRoot = [System.IO.Path]::GetFullPath($VelocityHome)
} catch {
  $VelocityRoot = $null
}
$VelocityRootExists = -not [string]::IsNullOrWhiteSpace($VelocityRoot) -and
  (Test-Path -LiteralPath $VelocityRoot -PathType Container)
Add-Check "velocity_home" $VelocityRootExists (
  "path=$(Format-Path $VelocityRoot) exists=$($VelocityRootExists.ToString().ToLowerInvariant())"
)

$Java = Resolve-Java $JavaExecutable
$Java21 = Test-Java21 $Java
Add-Check "java_21" $Java21 (
  "path=$(Format-Path $Java) java21=$($Java21.ToString().ToLowerInvariant())"
)

$ResolvedVelocityJar = if ($VelocityRootExists) {
  Resolve-VelocityJar $VelocityJar $VelocityRoot
} else {
  $null
}
$VelocityBuild = Get-VelocityBuild $ResolvedVelocityJar
$VelocityBuild606 = $VelocityBuild -eq "606"
Add-Check "velocity_build_606" $VelocityBuild606 (
  "path=$(Format-Path $ResolvedVelocityJar) build606=$($VelocityBuild606.ToString().ToLowerInvariant())"
)

$PluginRoot = if ($VelocityRootExists) { Join-Path $VelocityRoot "plugins" } else { $null }
$PluginRootExists = -not [string]::IsNullOrWhiteSpace($PluginRoot) -and
  (Test-Path -LiteralPath $PluginRoot -PathType Container)
$PluginJars = if ($PluginRootExists) {
  @(Get-ChildItem -LiteralPath $PluginRoot -File -Filter "*.jar" -Force -Recurse `
    -ErrorAction SilentlyContinue)
} else {
  @()
}
$JarIdentities = [System.Collections.Generic.List[object]]::new()
$PluginJarsReadable = $PluginRootExists
foreach ($PluginJar in $PluginJars) {
  $Identity = Read-PluginJarIdentity $PluginJar
  if ($null -eq $Identity) {
    $PluginJarsReadable = $false
    continue
  }
  $JarIdentities.Add($Identity)
}
Add-Check "plugin_jar_inspection" $PluginJarsReadable (
  "path=$(Format-Path $PluginRoot) readable=$($PluginJarsReadable.ToString().ToLowerInvariant())"
)

$StarxJars = @($JarIdentities | Where-Object { $_.IsStarx } | ForEach-Object { $_.Jar })
$SingleStarxJar = $StarxJars.Count -eq 1
$DeployedJar = if ($SingleStarxJar) { $StarxJars[0].FullName } else { $null }
Add-Check "starx_jar_count" $SingleStarxJar (
  "single=$($SingleStarxJar.ToString().ToLowerInvariant()) path=$(Format-Path $DeployedJar)"
)

$ExternalLimboJars = @($JarIdentities | Where-Object { $_.IsExternalLimbo })
$NoExternalLimbo = $ExternalLimboJars.Count -eq 0
Add-Check "external_limboapi" $NoExternalLimbo (
  "absent=$($NoExternalLimbo.ToString().ToLowerInvariant())"
)

$ResolvedCandidateJar = Resolve-FullPath $CandidateJar (Get-Location).Path
$CandidateSha = Get-Sha256 $ResolvedCandidateJar
$DeployedSha = Get-Sha256 $DeployedJar
$HashesMatch = -not [string]::IsNullOrWhiteSpace($CandidateSha) -and
  -not [string]::IsNullOrWhiteSpace($DeployedSha) -and
  $CandidateSha -ceq $DeployedSha
$HashDetail = if ($null -ne $CandidateSha -and $null -ne $DeployedSha) {
  "candidate_sha256=$CandidateSha deployed_sha256=$DeployedSha match=$($HashesMatch.ToString().ToLowerInvariant())"
} else {
  "candidate_path=$(Format-Path $ResolvedCandidateJar) deployed_path=$(Format-Path $DeployedJar) match=false"
}
Add-Check "candidate_hash" $HashesMatch $HashDetail

$StarxConfigPath = if ($VelocityRootExists) {
  Join-Path $VelocityRoot "plugins\starx\config.yml"
} else {
  $null
}
$VelocityConfigPath = if ($VelocityRootExists) {
  Join-Path $VelocityRoot "velocity.toml"
} else {
  $null
}
$ResolvedPaperGlobal = Resolve-FullPath $PaperGlobalConfig (Get-Location).Path
$ResolvedPaperProperties = Resolve-FullPath $PaperServerProperties (Get-Location).Path
$RuntimeSyntax = Test-RuntimeConfigSyntax `
  $Java `
  $ResolvedVelocityJar `
  $StarxConfigPath `
  $VelocityConfigPath `
  $ResolvedPaperGlobal `
  $ResolvedPaperProperties
$StarxConfig = if ($RuntimeSyntax.StarxYaml) {
  Read-StarxConfigDocument $StarxConfigPath
} else {
  $null
}
$StarxConfigSyntax = $null -ne $StarxConfig
Add-Check "starx_config_syntax" $StarxConfigSyntax (
  "path=$(Format-Path $StarxConfigPath) valid=$($StarxConfigSyntax.ToString().ToLowerInvariant())"
)
$HasModuleRoot = $null -ne $StarxConfig -and
  $StarxConfig.Mappings.ContainsKey("modules.starx.uworld")
$HasUworldRoot = $null -ne $StarxConfig -and
  $StarxConfig.Mappings.ContainsKey("uworld")
$ModuleEnabled = $HasModuleRoot -and
  $StarxConfig.Scalars.ContainsKey("modules.starx.uworld.enabled") -and
  $StarxConfig.Scalars["modules.starx.uworld.enabled"] -ieq "true"
$UworldEnabled = $HasUworldRoot -and
  $StarxConfig.Scalars.ContainsKey("uworld.enabled") -and
  $StarxConfig.Scalars["uworld.enabled"] -ieq "true"
$LegacyConfig = $null -ne $StarxConfig -and (
  $StarxConfig.Mappings.ContainsKey("modules.starx.limbo") -or
  $StarxConfig.Mappings.ContainsKey("limbo")
)
$PrimaryConfig = $HasModuleRoot -and $HasUworldRoot -and
  $ModuleEnabled -and $UworldEnabled
Add-Check "uworld_config" $PrimaryConfig (
  "path=$(Format-Path $StarxConfigPath) module_root=$($HasModuleRoot.ToString().ToLowerInvariant()) uworld_root=$($HasUworldRoot.ToString().ToLowerInvariant()) module_enabled=$($ModuleEnabled.ToString().ToLowerInvariant()) uworld_enabled=$($UworldEnabled.ToString().ToLowerInvariant()) legacy=$($LegacyConfig.ToString().ToLowerInvariant())"
)

$VelocityConfig = if ($RuntimeSyntax.VelocityToml) {
  Read-VelocityConfig $VelocityConfigPath
} else {
  $null
}
$VelocityConfigSyntax = $null -ne $VelocityConfig
Add-Check "velocity_config_syntax" $VelocityConfigSyntax (
  "path=$(Format-Path $VelocityConfigPath) valid=$($VelocityConfigSyntax.ToString().ToLowerInvariant())"
)
$TargetName = ""
if ($null -ne $StarxConfig -and
    $StarxConfig.Scalars.ContainsKey("uworld.auth.target-server")) {
  $TargetName = $StarxConfig.Scalars["uworld.auth.target-server"]
} elseif ($null -ne $StarxConfig -and
    $StarxConfig.Scalars.ContainsKey("limbo.auth.target-server")) {
  # Legacy target is diagnostic-only; uworld_config still fails above.
  $TargetName = $StarxConfig.Scalars["limbo.auth.target-server"]
}
$TargetConfigured = -not [string]::IsNullOrWhiteSpace($TargetName)
$TargetAddress = if ($TargetConfigured -and $null -ne $VelocityConfig -and
    $VelocityConfig.Servers.ContainsKey($TargetName)) {
  $VelocityConfig.Servers[$TargetName]
} else {
  $null
}
$TargetEndpoint = Convert-ServerEndpoint $TargetAddress
$TargetRegistered = $TargetConfigured -and $null -ne $TargetEndpoint
$TargetPort = if ($null -ne $TargetEndpoint) { $TargetEndpoint.Port } else { 0 }
Add-Check "target_registered" $TargetRegistered (
  "configured=$($TargetConfigured.ToString().ToLowerInvariant()) registered=$($TargetRegistered.ToString().ToLowerInvariant()) port=$TargetPort"
)

$BackendReachable = if (-not $RequireBackend) {
  $true
} elseif ($TargetRegistered) {
  Test-TcpEndpoint $TargetEndpoint.HostName $TargetEndpoint.Port
} else {
  $false
}
Add-Check "backend_reachable" $BackendReachable (
  "required=$($RequireBackend.IsPresent.ToString().ToLowerInvariant()) reachable=$($BackendReachable.ToString().ToLowerInvariant()) port=$TargetPort"
)

$VelocitySecretPath = if ($null -ne $VelocityConfig -and
    -not [string]::IsNullOrWhiteSpace($VelocityConfig.SecretFile)) {
  Resolve-FullPath $VelocityConfig.SecretFile $VelocityRoot
} else {
  $null
}
$VelocitySecret = Read-SecretFile $VelocitySecretPath
$VelocityModern = $null -ne $VelocityConfig -and
  $VelocityConfig.ForwardingMode -ieq "modern" -and
  -not [string]::IsNullOrWhiteSpace($VelocitySecret)
Add-Check "velocity_modern_forwarding" $VelocityModern (
  "path=$(Format-Path $VelocitySecretPath) modern=$($VelocityModern.ToString().ToLowerInvariant()) secret_nonempty=$(((-not [string]::IsNullOrWhiteSpace($VelocitySecret))).ToString().ToLowerInvariant())"
)

$PaperConfig = if ($RuntimeSyntax.PaperYaml) {
  Read-YamlDocument $ResolvedPaperGlobal
} else {
  $null
}
$PaperConfigSyntax = $null -ne $PaperConfig
Add-Check "paper_config_syntax" $PaperConfigSyntax (
  "path=$(Format-Path $ResolvedPaperGlobal) valid=$($PaperConfigSyntax.ToString().ToLowerInvariant())"
)
$PaperVelocityEnabled = $null -ne $PaperConfig -and
  $PaperConfig.Scalars.ContainsKey("proxies.velocity.enabled") -and
  $PaperConfig.Scalars["proxies.velocity.enabled"] -ieq "true"
$PaperForwardingOnlineMode = if ($null -ne $PaperConfig -and
    $PaperConfig.Scalars.ContainsKey("proxies.velocity.online-mode")) {
  $PaperConfig.Scalars["proxies.velocity.online-mode"].Trim().ToLowerInvariant()
} else {
  ""
}
$PaperSecret = if ($null -ne $PaperConfig -and
    $PaperConfig.Scalars.ContainsKey("proxies.velocity.secret")) {
  $PaperConfig.Scalars["proxies.velocity.secret"].Trim()
} else {
  $null
}
$PaperForwarding = $PaperVelocityEnabled -and
  -not [string]::IsNullOrWhiteSpace($PaperSecret)
Add-Check "paper_velocity_forwarding" $PaperForwarding (
  "path=$(Format-Path $ResolvedPaperGlobal) enabled=$($PaperVelocityEnabled.ToString().ToLowerInvariant()) secret_nonempty=$(((-not [string]::IsNullOrWhiteSpace($PaperSecret))).ToString().ToLowerInvariant())"
)

$SecretsMatch = -not [string]::IsNullOrWhiteSpace($VelocitySecret) -and
  -not [string]::IsNullOrWhiteSpace($PaperSecret) -and
  [string]::Equals($VelocitySecret, $PaperSecret, [System.StringComparison]::Ordinal)
Add-Check "forwarding_secret_match" $SecretsMatch (
  "velocity_nonempty=$(((-not [string]::IsNullOrWhiteSpace($VelocitySecret))).ToString().ToLowerInvariant()) paper_nonempty=$(((-not [string]::IsNullOrWhiteSpace($PaperSecret))).ToString().ToLowerInvariant()) match=$($SecretsMatch.ToString().ToLowerInvariant())"
)

$VelocityOnlineMode = if ($null -ne $VelocityConfig) {
  $VelocityConfig.OnlineMode
} else {
  ""
}
$ForwardingOnlineModesMatch = $VelocityOnlineMode -in @("true", "false") -and
  $PaperForwardingOnlineMode -in @("true", "false") -and
  $VelocityOnlineMode -ceq $PaperForwardingOnlineMode
Add-Check "forwarding_online_mode_match" $ForwardingOnlineModesMatch (
  "velocity=$VelocityOnlineMode paper=$PaperForwardingOnlineMode match=$($ForwardingOnlineModesMatch.ToString().ToLowerInvariant())"
)

$PaperPropertiesSyntax = $RuntimeSyntax.PaperProperties
$DuplicatePaperProperties = if ([string]::IsNullOrWhiteSpace(
    $RuntimeSyntax.DuplicatePaperProperties
  )) {
  "none"
} else {
  $RuntimeSyntax.DuplicatePaperProperties
}
Add-Check "paper_server_properties_syntax" $PaperPropertiesSyntax (
  "path=$(Format-Path $ResolvedPaperProperties) valid=$($PaperPropertiesSyntax.ToString().ToLowerInvariant()) duplicate_keys=$DuplicatePaperProperties"
)

$PaperOnlineModeValue = if ($PaperPropertiesSyntax) {
  Read-Property $ResolvedPaperProperties "online-mode"
} else {
  $null
}
$PaperOnlineModeDisabled = $PaperOnlineModeValue -ieq "false"
Add-Check "paper_online_mode" $PaperOnlineModeDisabled (
  "path=$(Format-Path $ResolvedPaperProperties) online_mode_false=$($PaperOnlineModeDisabled.ToString().ToLowerInvariant())"
)

$PaperServerPortValue = if ($PaperPropertiesSyntax) {
  Read-Property $ResolvedPaperProperties "server-port"
} else {
  $null
}
$PaperServerPort = 0
$PaperServerPortValid = [int]::TryParse(
  $PaperServerPortValue,
  [ref] $PaperServerPort
) -and $PaperServerPort -ge 1 -and $PaperServerPort -le 65535
$PaperTargetPortMatches = $TargetRegistered -and
  $PaperServerPortValid -and
  $PaperServerPort -eq $TargetPort
Add-Check "paper_target_port" $PaperTargetPortMatches (
  "registered=$($TargetRegistered.ToString().ToLowerInvariant()) paper_port=$PaperServerPort target_port=$TargetPort match=$($PaperTargetPortMatches.ToString().ToLowerInvariant())"
)

$PaperServerIp = if ($PaperPropertiesSyntax) {
  Read-Property $ResolvedPaperProperties "server-ip"
} else {
  $null
}
$PaperServerIpConfigured = -not [string]::IsNullOrWhiteSpace($PaperServerIp)
$PaperServerBindingPrivate = Test-PrivateServerAddress $PaperServerIp
$PaperTargetHostMatches = $TargetRegistered -and
  (Test-ServerTargetIdentity $TargetEndpoint.HostName $PaperServerIp)
Add-Check "paper_target_host" $PaperTargetHostMatches (
  "registered=$($TargetRegistered.ToString().ToLowerInvariant()) binding_specific=$($PaperServerBindingPrivate.ToString().ToLowerInvariant()) same_address=$($PaperTargetHostMatches.ToString().ToLowerInvariant())"
)
Add-Check "paper_server_binding" $PaperServerBindingPrivate (
  "configured=$($PaperServerIpConfigured.ToString().ToLowerInvariant()) private_or_loopback=$($PaperServerBindingPrivate.ToString().ToLowerInvariant())"
)

$DatabaseType = if ($null -ne $StarxConfig -and
    $StarxConfig.Scalars.ContainsKey("database.type")) {
  $StarxConfig.Scalars["database.type"]
} else {
  "sqlite"
}
if ([string]::IsNullOrWhiteSpace($DatabaseType)) {
  $DatabaseType = "sqlite"
}
$DatabaseValue = if ($null -ne $StarxConfig -and
    $StarxConfig.Scalars.ContainsKey("database.database")) {
  $StarxConfig.Scalars["database.database"]
} else {
  "plugins/starx/data.db"
}
if ([string]::IsNullOrWhiteSpace($DatabaseValue)) {
  $DatabaseValue = "plugins/starx/data.db"
}
$DatabaseUrl = if ($null -ne $StarxConfig -and
    $StarxConfig.Scalars.ContainsKey("database.url")) {
  $StarxConfig.Scalars["database.url"].Trim()
} else {
  ""
}
$EffectiveJdbcUrl = if (-not [string]::IsNullOrWhiteSpace($DatabaseUrl)) {
  $DatabaseUrl
} elseif ($DatabaseType -ieq "sqlite") {
  "jdbc:sqlite:$DatabaseValue"
} else {
  $null
}
$SqliteDatabase = -not [string]::IsNullOrWhiteSpace($EffectiveJdbcUrl) -and
  $EffectiveJdbcUrl.StartsWith(
    "jdbc:sqlite:",
    [System.StringComparison]::OrdinalIgnoreCase
  )
$SqliteParent = if ($SqliteDatabase) {
  Resolve-SqliteParent $EffectiveJdbcUrl $VelocityRoot
} else {
  $null
}
$SqliteWriteProbe = if ($SqliteDatabase) {
  Test-DirectoryWritableAsIdentity $SqliteParent $ServiceIdentity
} else {
  [pscustomobject]@{ Passed = $false; Method = "not-sqlite" }
}
$SqliteParentWritable = $SqliteDatabase -and $SqliteWriteProbe.Passed
Add-Check "sqlite_parent_writable" $SqliteParentWritable (
  "path=$(Format-Path $SqliteParent) sqlite=$($SqliteDatabase.ToString().ToLowerInvariant()) service_identity=$(Format-Path $ServiceIdentity) method=$($SqliteWriteProbe.Method) writable=$($SqliteParentWritable.ToString().ToLowerInvariant())"
)

foreach ($Check in $Checks) {
  Write-Output "CHECK name=$($Check.Name) status=$($Check.Status) detail=$($Check.Detail)"
}

$Failed = @($Checks | Where-Object { $_.Status -eq "FAIL" }).Count
if ($Failed -eq 0) {
  Write-Output "UWORLD_ENVIRONMENT=PASS"
  exit 0
}

Write-Output "UWORLD_ENVIRONMENT=FAIL"
exit 1
