[CmdletBinding()]
param(
  [Parameter(Position=0)][string]$Target,
  [Parameter(Position=1,ValueFromRemainingArguments=$true)][string[]]$GradleArgs
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if ([string]::IsNullOrWhiteSpace($Target)) { $Target = 'forge-1.20.1' }
$javaHome = $env:MC_JAVA_HOME_21
if ([string]::IsNullOrWhiteSpace($javaHome)) { $javaHome = $env:JAVA_HOME }
if ([string]::IsNullOrWhiteSpace($javaHome)) { throw 'Set MC_JAVA_HOME_21 (bootstrap) or JAVA_HOME.' }
$java = Join-Path $javaHome 'bin\java.exe'
if (!(Test-Path -LiteralPath $java)) { throw "Java executable not found: $java" }
Push-Location $root
try {
  # --daemon, not --no-daemon: this bootstrap build runs on every launch, and a
  # single-use JVM made it pay a full cold start each time (it is UP-TO-DATE in
  # about a second once the daemon is warm). The explicit flag also beats a
  # -Dorg.gradle.daemon=false coming from an inherited GRADLE_OPTS. GRADLE_OPTS is
  # sanitized for the real build inside TargetGradleLauncher, so all OS frontends
  # get the same behaviour.
  & (Join-Path $root 'gradlew.bat') ':tools:target-launcher:installDist' '--daemon'
  if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
  & (Join-Path $root 'tools\target-launcher\dist\bin\target-launcher.bat') $Target @GradleArgs
  exit $LASTEXITCODE
} finally { Pop-Location }
