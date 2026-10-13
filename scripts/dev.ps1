<#
.SYNOPSIS
  Starts the whole DinoParty dev stack: PostgreSQL, backend and frontend. Ctrl+C stops everything.
.PARAMETER Observability
  Also start the OpenTelemetry Collector and Aspire Dashboard (http://localhost:18888); backend, browser and PostgreSQL telemetry flow into it.
.PARAMETER Stop
  Stop the Docker services (database and dashboard) and exit. Data volume is kept.
#>
param([switch]$Observability, [switch]$Stop)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

if (-not (Test-Path .env)) {
  Copy-Item .env.example .env
  Write-Host "Created .env from .env.example (placeholders; edit if needed)."
}
$base = @('compose', '--env-file', '.env', '-f', 'deploy/compose.dev.yml')
$compose = $base
if ($Observability -or $Stop) { $compose = $base + @('-f', 'deploy/compose.observability.yml') }

if ($Stop) { $ErrorActionPreference = 'Continue'; & docker @compose stop; exit $LASTEXITCODE }

# The backend is compiled for Java 21; an older JDK on PATH/JAVA_HOME fails at startup with
# UnsupportedClassVersionError. Pick a JDK 21 explicitly instead of trusting the environment.
function Test-Jdk21($home_) {
  $javac = Join-Path $home_ 'bin\javac.exe'
  (Test-Path $javac) -and ((& $javac -version 2>&1 | Out-String) -match 'javac 21\.')
}
if (-not ($env:JAVA_HOME -and (Test-Jdk21 $env:JAVA_HOME))) {
  $candidates = @('C:\Program Files\Java', 'C:\Program Files\Eclipse Adoptium', 'C:\Program Files\Microsoft',
      'C:\Program Files\Amazon Corretto', 'C:\Program Files\Zulu', "$env:USERPROFILE\.jdks") |
    Where-Object { Test-Path $_ } | ForEach-Object { Get-ChildItem $_ -Directory -Filter '*21*' } |
    Where-Object { Test-Jdk21 $_.FullName }
  if (-not $candidates) { throw "JDK 21 not found. Install it and set JAVA_HOME (current: '$env:JAVA_HOME')." }
  $env:JAVA_HOME = @($candidates)[0].FullName
  $env:PATH = "$env:JAVA_HOMEin;$env:PATH"
  Write-Host "Using JDK 21 at $env:JAVA_HOME"
}
if (-not (Test-Path frontend/node_modules)) { npm --prefix frontend ci }

$services = @('db')
if ($Observability) {
  $services += 'otel-collector', 'aspire-dashboard'
  $env:OTEL_ENABLED = 'true'       # backend export
  $env:VITE_OTEL_ENABLED = 'true'  # browser export
}
# Docker writes progress to stderr, which PowerShell 5.1 treats as an error under 'Stop'.
$ErrorActionPreference = 'Continue'
& docker @compose up -d --wait @services
$ErrorActionPreference = 'Stop'
if ($LASTEXITCODE -ne 0) { throw "docker compose failed" }

$procs = @()
try {
  $procs += Start-Process -NoNewWindow -PassThru -FilePath "$root\backend\mvnw.cmd" `
    -ArgumentList '-f', 'backend', 'spring-boot:run', '-Dspring-boot.run.profiles=dev'
  $procs += Start-Process -NoNewWindow -PassThru -FilePath 'npm.cmd' `
    -ArgumentList '--prefix', 'frontend', 'run', 'dev'

  Write-Host "`nApp:      http://localhost:5173"
  Write-Host "Backend:  http://localhost:8080/api/v1/health"
  if ($Observability) { Write-Host "Telemetry: http://localhost:18888" }
  Write-Host "Ctrl+C to stop backend and frontend (database keeps running; use -Stop).`n"
  # Wait-Process -Any needs PowerShell 7; poll so this also works on Windows PowerShell 5.1.
  while (-not ($procs | Where-Object { $_.HasExited })) { Start-Sleep -Seconds 1 }
} finally {
  foreach ($p in $procs) { if (-not $p.HasExited) { taskkill /PID $p.Id /T /F | Out-Null } }
}
