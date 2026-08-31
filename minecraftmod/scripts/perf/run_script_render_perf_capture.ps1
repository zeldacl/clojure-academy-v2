param(
  [ValidateSet('forge-1.20.1','fabric-1.20.1','fabric-1.21.1','neoforge-1.21.1','neoforge-26.2','fabric-26.2')]
  [string]$PlatformTarget = 'forge-1.20.1',

  [Parameter(Mandatory = $true)]
  [string]$Scenario,

  [Parameter(Mandatory = $true)]
  [string]$Mode,

  [switch]$SkipCheckClojure
)

$ErrorActionPreference = 'Stop'

$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$outDir = Join-Path $root "build\reports\script-render-perf\$PlatformTarget\$Scenario\$Mode"
New-Item -ItemType Directory -Path $outDir -Force | Out-Null

$jfrFile = Join-Path $outDir "capture-$timestamp.jfr"
$logFile = Join-Path $outDir "run-$timestamp.log"

$task = ':platform:runClient'
$checkTask = ':platform:checkClojure'
$targetGradle = Join-Path $root 'scripts\target-gradle.ps1'
if (!(Test-Path -LiteralPath $targetGradle -PathType Leaf)) {
  throw "Target launcher script is missing: $targetGradle"
}

# The build logic attaches this property to the forked game JavaExec JVM,
# rather than to the Gradle daemon, so the recording contains game samples.
$gradleArgs = @(
  $PlatformTarget,
  $task,
  '--no-daemon',
  '--console=plain',
  "-PperfJfrFile=$jfrFile"
)
if ($SkipCheckClojure.IsPresent) {
  $gradleArgs += @('-x', $checkTask)
}

Write-Host "[perf] root      : $root"
Write-Host "[perf] target    : $PlatformTarget"
Write-Host "[perf] scenario  : $Scenario"
Write-Host "[perf] mode      : $Mode"
Write-Host "[perf] game-jfr  : $jfrFile"
Write-Host "[perf] log       : $logFile"

Push-Location $root
try {
  # target-gradle.ps1 resolves the catalog-selected wrapper and Java toolchain;
  # this keeps the capture command identical across all six targets.
  $cmd = @('powershell.exe', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $targetGradle) +
    $gradleArgs
  "[perf] command: $($cmd -join ' ')" | Out-File -FilePath $logFile -Encoding utf8 -Append

  $proc = Start-Process -FilePath $cmd[0] -ArgumentList $cmd[1..($cmd.Length-1)] -PassThru -NoNewWindow -RedirectStandardOutput $logFile -RedirectStandardError $logFile

  Write-Host "[perf] client started (pid=$($proc.Id))."
  Write-Host "[perf] Do the in-game perf route manually (low/medium/stress for this mode), then close client normally to flush JFR."
  Write-Host "[perf] Output files will be written under: $outDir"

  $proc.WaitForExit()
  Write-Host "[perf] client exited with code: $($proc.ExitCode)"

  if (Test-Path $jfrFile) {
    Write-Host "[perf] JFR captured: $jfrFile"
  } else {
    Write-Warning "[perf] JFR file not found. Check log: $logFile"
  }
}
finally {
  Pop-Location
}
