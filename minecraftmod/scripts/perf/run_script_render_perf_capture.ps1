param(
  [ValidateSet('forge','fabric')]
  [string]$Loader = 'forge',

  [Parameter(Mandatory = $true)]
  [string]$Scenario,

  [Parameter(Mandatory = $true)]
  [string]$Mode,

  [switch]$SkipCheckClojure
)

$ErrorActionPreference = 'Stop'

$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$outDir = Join-Path $root "build\reports\script-render-perf\$Loader\$Scenario\$Mode"
New-Item -ItemType Directory -Path $outDir -Force | Out-Null

$jfrFile = Join-Path $outDir "capture-$timestamp.jfr"
$logFile = Join-Path $outDir "run-$timestamp.log"

$task = if ($Loader -eq 'forge') { ':forge-1.20.1:runClient' } else { ':fabric-1.20.1:runClient' }
$checkTask = if ($Loader -eq 'forge') { ':forge-1.20.1:checkClojure' } else { ':fabric-1.20.1:checkClojure' }

$gradleArgs = @($task, '--no-daemon', '--console=plain')
if ($SkipCheckClojure.IsPresent) {
  $gradleArgs += @('-x', $checkTask)
}

$jfrArgs = @(
  '-XX:StartFlightRecording=name=ScriptRenderPerf,settings=profile,dumponexit=true,filename=' + $jfrFile,
  '-XX:FlightRecorderOptions=stackdepth=256'
)

Write-Host "[perf] root      : $root"
Write-Host "[perf] loader    : $Loader"
Write-Host "[perf] scenario  : $Scenario"
Write-Host "[perf] mode      : $Mode"
Write-Host "[perf] jfr       : $jfrFile"
Write-Host "[perf] log       : $logFile"

Push-Location $root
try {
  $cmd = @('cmd','/c','gradlew.bat') + $gradleArgs + @('-Dorg.gradle.jvmargs=' + ($jfrArgs -join ' '))
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
