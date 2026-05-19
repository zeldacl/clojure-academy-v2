[CmdletBinding()]
param(
    [string[]]$TaskSpecs = @(
        ':ac:compileClojure',
        ':mcmod:compileClojure',
        ':forge-1.20.1:compileClojure',
        ':fabric-1.20.1:compileClojure',
        'verifyArchitectureBoundaries',
        'runAcUnitTests runMcmodUnitTests',
        'verifyCurrentPlatforms',
        'verifyForgeTesting'
    ),
    [int]$Iterations = 1,
    [switch]$CleanBeforeEach,
    [switch]$WarmDaemon,
    [string[]]$ExtraGradleArgs = @(),
    [string]$OutputDir = 'build/reports/gate-performance'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot '..\..')
Push-Location $repoRoot
try {
    $timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $reportDir = Join-Path $repoRoot (Join-Path $OutputDir $timestamp)
    New-Item -ItemType Directory -Force -Path $reportDir | Out-Null

    $javaVersion = (cmd /c 'java -version 2>&1') -join "`n"

    $metadata = [ordered]@{
        timestamp = (Get-Date).ToString('o')
        repoRoot = $repoRoot.Path
        os = (Get-CimInstance Win32_OperatingSystem | Select-Object -ExpandProperty Caption)
        osVersion = (Get-CimInstance Win32_OperatingSystem | Select-Object -ExpandProperty Version)
        java = $javaVersion
        gradleOpts = $env:GRADLE_OPTS
        javaHome = $env:JAVA_HOME
        processorCount = $env:NUMBER_OF_PROCESSORS
        cleanBeforeEach = [bool]$CleanBeforeEach
        warmDaemon = [bool]$WarmDaemon
        iterations = $Iterations
        extraGradleArgs = $ExtraGradleArgs
        taskSpecs = $TaskSpecs
    }
    $metadata | ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 (Join-Path $reportDir 'metadata.json')

    if ($WarmDaemon) {
        Write-Host '[gate-perf] warming Gradle daemon with help task...'
        cmd /c '.\gradlew.bat help > nul 2>&1'
    }

    $rows = New-Object System.Collections.Generic.List[object]

    foreach ($taskSpec in $TaskSpecs) {
        for ($iteration = 1; $iteration -le $Iterations; $iteration++) {
            $safeName = ($taskSpec -replace '[^A-Za-z0-9_.-]+', '_').Trim('_')
            $logPath = Join-Path $reportDir ("{0:D2}-{1}.log" -f $iteration, $safeName)
            $gradleArgs = New-Object System.Collections.Generic.List[string]
            if ($CleanBeforeEach) {
                $gradleArgs.Add('clean')
            }
            foreach ($part in ($taskSpec -split '\s+')) {
                if (-not [string]::IsNullOrWhiteSpace($part)) {
                    $gradleArgs.Add($part)
                }
            }
            foreach ($arg in $ExtraGradleArgs) {
                $gradleArgs.Add($arg)
            }

            $command = '.\gradlew.bat ' + (($gradleArgs | ForEach-Object { $_ }) -join ' ')
            Write-Host ("[gate-perf] iteration={0} task='{1}'" -f $iteration, $taskSpec)
            $started = Get-Date
            $sw = [System.Diagnostics.Stopwatch]::StartNew()
            $process = Start-Process -FilePath 'cmd.exe' -ArgumentList @('/c', "$command > `"$logPath`" 2>&1") -Wait -PassThru -NoNewWindow
            $sw.Stop()

            $outcomes = @{}
            if (Test-Path $logPath) {
                Select-String -Path $logPath -Pattern '^> Task\s+\S+(?:\s+(UP-TO-DATE|FROM-CACHE|NO-SOURCE|SKIPPED|FAILED))?\s*$' | ForEach-Object {
                    $outcome = if ($_.Matches[0].Groups[1].Success) { $_.Matches[0].Groups[1].Value } else { 'EXECUTED' }
                    $current = 0
                    if ($outcomes.ContainsKey($outcome)) {
                        $current = [int]$outcomes[$outcome]
                    }
                    $outcomes[$outcome] = 1 + $current
                }
            }
            $outcomeSummary = (($outcomes.GetEnumerator() | Sort-Object Name | ForEach-Object { "$($_.Name)=$($_.Value)" }) -join ';')

            $rows.Add([pscustomobject]@{
                started = $started.ToString('o')
                taskSpec = $taskSpec
                iteration = $iteration
                cleanBeforeEach = [bool]$CleanBeforeEach
                warmDaemon = [bool]$WarmDaemon
                extraGradleArgs = ($ExtraGradleArgs -join ' ')
                elapsedMs = [long]$sw.ElapsedMilliseconds
                exitCode = [int]$process.ExitCode
                outcomes = $outcomeSummary
                log = $logPath
            })

            if ($process.ExitCode -ne 0) {
                Write-Warning ("[gate-perf] task '{0}' failed with exit code {1}; see {2}" -f $taskSpec, $process.ExitCode, $logPath)
            }
        }
    }

    $csvPath = Join-Path $reportDir 'summary.csv'
    $rows | Export-Csv -NoTypeInformation -Encoding UTF8 $csvPath
    Write-Host "[gate-perf] wrote $csvPath"
}
finally {
    Pop-Location
}