[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

function Fail([string]$Message) {
    [Console]::Error.WriteLine("ERROR: $Message")
    exit 1
}

function Quote-PowerShellString([string]$Value) {
    return "'" + $Value.Replace("'", "''") + "'"
}

$projectRoot = Split-Path -Parent $PSScriptRoot
$frontendDir = Join-Path $projectRoot 'frontend'
$backendDir = Join-Path $projectRoot 'backend'
$composeFile = Join-Path $projectRoot 'docker-compose.yml'
$envFile = Join-Path $projectRoot '.env'

foreach ($path in @($frontendDir, $backendDir, $composeFile, $envFile)) {
    if (-not (Test-Path -LiteralPath $path)) {
        Fail "Project structure is incomplete: missing $path"
    }
}

if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
    Fail 'Node.js was not found. Install Node.js and run this script again.'
}

if (-not (Get-Command npm -ErrorAction SilentlyContinue)) {
    Fail 'npm was not found. Repair the Node.js/npm installation and try again.'
}

if (-not (Test-Path -LiteralPath (Join-Path $frontendDir 'node_modules'))) {
    Write-Host 'frontend/node_modules was not found. Running npm ci...' -ForegroundColor Yellow
    Push-Location $frontendDir
    try {
        npm ci
        if ($LASTEXITCODE -ne 0) {
            Fail 'Frontend dependency installation failed. Check npm configuration and network access.'
        }
    } finally {
        Pop-Location
    }
}

if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    Fail 'Java was not found. The backend requires Java 21 or later.'
}

$javaVersionOutput = (cmd.exe /c 'java -version 2>&1' | Out-String)
if ($javaVersionOutput -notmatch 'version "(\d+)') {
    Fail "Unable to determine the Java version: $javaVersionOutput"
}

if ([int]$Matches[1] -lt 21) {
    Fail "Java $($Matches[1]) is too old. The backend requires Java 21 or later."
}

$mavenWrapper = Join-Path $backendDir 'mvnw.cmd'
if (Test-Path -LiteralPath $mavenWrapper) {
    $backendStartCommand = 'cmd.exe /c "' + $mavenWrapper + '" spring-boot:run'
} else {
    if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
        Fail 'Maven was not found and backend/mvnw.cmd is unavailable. Install Maven and try again.'
    }
    $backendStartCommand = 'mvn spring-boot:run'
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Fail 'Docker was not found. The backend depends on the local infrastructure in docker-compose.yml.'
}

$requiredServices = @('mysql', 'redis', 'minio', 'rocketmq-namesrv', 'rocketmq-broker', 'qdrant')
$runningServices = @(docker compose -f $composeFile ps --services --status running)
if ($LASTEXITCODE -ne 0) {
    Fail 'Unable to check Docker Compose services. Ensure Docker Desktop is running.'
}

$missingServices = @($requiredServices | Where-Object { $_ -notin $runningServices })
if ($missingServices.Count -gt 0) {
    Write-Host "Starting missing development infrastructure: $($missingServices -join ', ')" -ForegroundColor Yellow
    docker compose -f $composeFile up -d
    if ($LASTEXITCODE -ne 0) {
        Fail 'Docker Compose infrastructure failed to start. Check Docker Desktop and docker-compose.yml.'
    }
} else {
    Write-Host 'Development infrastructure is already running; it will not be started again.' -ForegroundColor Green
}

$quotedFrontendDir = Quote-PowerShellString $frontendDir
$quotedBackendDir = Quote-PowerShellString $backendDir
$frontendCommand = '$host.UI.RawUI.WindowTitle = ''VideoAgent Frontend''; Set-Location -LiteralPath ' +
    $quotedFrontendDir + '; npm run dev'
$backendCommand = '$host.UI.RawUI.WindowTitle = ''VideoAgent Backend''; Set-Location -LiteralPath ' +
    $quotedBackendDir + '; ' + $backendStartCommand

Start-Process -FilePath 'powershell.exe' -ArgumentList @('-NoExit', '-Command', $frontendCommand)
Start-Process -FilePath 'powershell.exe' -ArgumentList @('-NoExit', '-Command', $backendCommand)

Write-Host 'VideoAgent Frontend and VideoAgent Backend have been started in separate windows.' -ForegroundColor Green
