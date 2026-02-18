param(
  [switch]$SkipFrontendBuild,
  [switch]$SkipBackendBuild,
  [switch]$Foreground
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
if ([string]::IsNullOrWhiteSpace($repoRoot)) {
  $repoRoot = (Get-Location).Path
}
$repoRoot = (Resolve-Path $repoRoot).Path
Set-Location $repoRoot

function Resolve-RepoPath {
  param(
    [string]$Value,
    [string]$FallbackRelative
  )

  $candidate = if ([string]::IsNullOrWhiteSpace($Value)) {
    Join-Path $repoRoot $FallbackRelative
  } elseif ([System.IO.Path]::IsPathRooted($Value)) {
    $Value
  } else {
    Join-Path $repoRoot $Value
  }

  return [System.IO.Path]::GetFullPath($candidate)
}

function Get-ListeningPids {
  param([int]$Port)

  $result = @()
  $lines = netstat -ano | Select-String ":$Port" | Select-String "LISTENING"
  foreach ($line in $lines) {
    $parts = ($line.ToString().Trim() -split "\s+")
    if ($parts.Count -gt 0) {
      $candidatePid = $parts[$parts.Count - 1]
      if ($candidatePid -match '^[0-9]+$') {
        $result += [int]$candidatePid
      }
    }
  }

  return $result | Select-Object -Unique
}

if (-not $env:HOST) { $env:HOST = "0.0.0.0" }
if (-not $env:PORT) { $env:PORT = "8080" }
$env:DATA_DIR = Resolve-RepoPath -Value $env:DATA_DIR -FallbackRelative "data"
$env:WEB_UI_DIR = Resolve-RepoPath -Value $env:WEB_UI_DIR -FallbackRelative "web-ui/build/client"
$env:ASSETS_DIR = Resolve-RepoPath -Value $env:ASSETS_DIR -FallbackRelative "assets"
if (-not $env:JWT_ENABLED) { $env:JWT_ENABLED = "false" }
if (-not $env:ACCESS_PASSWORD) { $env:ACCESS_PASSWORD = "" }

$gradleExe = Join-Path $repoRoot ".tools/gradle-9.1.0/bin/gradle.bat"
if (-not (Test-Path $gradleExe)) {
  $gradleExe = Join-Path $repoRoot "gradlew.bat"
}
if (-not (Test-Path $gradleExe)) {
  throw "Cannot find Gradle launcher (.tools/gradle-9.1.0/bin/gradle.bat or gradlew.bat)."
}

$tmpDir = Join-Path $repoRoot ".tmp-backend"
New-Item -ItemType Directory -Path $tmpDir -Force | Out-Null
$pidFile = Join-Path $tmpDir "backend.pid"
$outLog = Join-Path $tmpDir "backend.out.log"
$errLog = Join-Path $tmpDir "backend.err.log"

function Stop-ExistingBackend {
  if (Test-Path $pidFile) {
    $rawPid = (Get-Content $pidFile -Raw).Trim()
    if ($rawPid -match '^[0-9]+$') {
      $existing = Get-Process -Id ([int]$rawPid) -ErrorAction SilentlyContinue
      if ($null -ne $existing) {
        Stop-Process -Id $existing.Id -Force -ErrorAction SilentlyContinue
        Start-Sleep -Milliseconds 400
      }
    }
    Remove-Item $pidFile -Force -ErrorAction SilentlyContinue
  }

  $listenerPids = Get-ListeningPids -Port ([int]$env:PORT)
  foreach ($listenPid in $listenerPids) {
    if ($listenPid -and $listenPid -ne $PID) {
      Stop-Process -Id $listenPid -Force -ErrorAction SilentlyContinue
    }
  }
}

Write-Host "[1/4] Stopping existing backend on port $($env:PORT)..."
Stop-ExistingBackend

if (-not $SkipFrontendBuild) {
  Write-Host "[2/4] Building web-ui..."
  Push-Location (Join-Path $repoRoot "web-ui")
  try {
    npm run build
    if ($LASTEXITCODE -ne 0) {
      throw "web-ui build failed"
    }
  }
  finally {
    Pop-Location
  }
}
else {
  Write-Host "[2/4] Skipped web-ui build"
}

if (-not $SkipBackendBuild) {
  Write-Host "[3/4] Building backend classes..."
  & $gradleExe ":backend-server:classes" "--no-daemon"
  if ($LASTEXITCODE -ne 0) {
    throw "backend classes build failed"
  }
}
else {
  Write-Host "[3/4] Skipped backend build"
}

if ($Foreground) {
  Write-Host "[4/4] Starting backend in foreground..."
  & $gradleExe ":backend-server:run" "--no-daemon"
  exit $LASTEXITCODE
}

Write-Host "[4/4] Starting backend in background..."
Remove-Item $outLog, $errLog -Force -ErrorAction SilentlyContinue
$launcher = Start-Process -FilePath $gradleExe -WorkingDirectory $repoRoot -ArgumentList ":backend-server:run --no-daemon" -PassThru -RedirectStandardOutput $outLog -RedirectStandardError $errLog

$healthUrl = "http://127.0.0.1:$($env:PORT)/api/system/health"
$ready = $false
for ($i = 0; $i -lt 90; $i++) {
  Start-Sleep -Milliseconds 1000
  try {
    $health = Invoke-RestMethod -Uri $healthUrl -TimeoutSec 2
    if ($health.status -eq "ok") {
      $ready = $true
      break
    }
  }
  catch {
    # keep waiting
  }
}

if (-not $ready) {
  Write-Host "Backend failed to become healthy. Recent logs:"
  if (Test-Path $outLog) { Get-Content $outLog -Tail 80 }
  if (Test-Path $errLog) { Get-Content $errLog -Tail 80 }
  throw "backend health check failed"
}

$listenerPid = (Get-ListeningPids -Port ([int]$env:PORT) | Select-Object -First 1)
if ($listenerPid) {
  Set-Content -Path $pidFile -Value $listenerPid -Encoding ascii
} else {
  Set-Content -Path $pidFile -Value $launcher.Id -Encoding ascii
}

Write-Host "Done."
Write-Host ("Launcher PID: " + $launcher.Id)
if ($listenerPid) { Write-Host ("Backend PID: " + $listenerPid) }
Write-Host ("Web URL: http://127.0.0.1:" + $env:PORT + "/")
Write-Host ("Health: " + $healthUrl)
Write-Host ("DATA_DIR: " + $env:DATA_DIR)
Write-Host ("WEB_UI_DIR: " + $env:WEB_UI_DIR)
Write-Host ("Logs: " + $outLog + " | " + $errLog)
