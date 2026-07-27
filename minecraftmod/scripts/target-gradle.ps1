[CmdletBinding()]
param(
  [Parameter(Position=0)][string]$Target,
  [Parameter(Position=1,ValueFromRemainingArguments=$true)][string[]]$GradleArgs
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if ([string]::IsNullOrWhiteSpace($Target)) { $Target = 'forge-1.20.1' }
$javaHome = $env:MC_JAVA_HOME_17
if ([string]::IsNullOrWhiteSpace($javaHome)) { $javaHome = $env:JAVA_HOME }
if ([string]::IsNullOrWhiteSpace($javaHome)) { throw 'Set MC_JAVA_HOME_17 or JAVA_HOME to a Java 17+ installation.' }
$java = Join-Path $javaHome 'bin\java.exe'
if (!(Test-Path -LiteralPath $java)) { throw "Java executable not found: $java" }
$major = (& $java -version 2>&1 | Select-String 'version').ToString()
if ($major -notmatch '"(1\.)?(\d+)') { throw "Cannot determine Java version from: $major" }
$version = [int]$Matches[2]
if ($version -lt 17) { throw "Target builds require Java 17 or newer; found $version" }
Push-Location $root
try {
  & (Join-Path $root 'gradlew.bat') ':tools:target-launcher:installDist' '--no-daemon'
  if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
  & (Join-Path $root 'tools\target-launcher\build\install\target-launcher\bin\target-launcher.bat') $Target @GradleArgs
  exit $LASTEXITCODE
} finally { Pop-Location }
