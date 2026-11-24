# Deploys a published image tag with docker compose on the home server (Windows PowerShell 5.1+).
# Called by the `deploy` job in .github/workflows/ci.yml from a self-hosted runner.
param(
    [Parameter(Mandatory = $true)][string]$Dir,
    [Parameter(Mandatory = $true)][string]$ImagePrefix,
    [Parameter(Mandatory = $true)][string]$Tag,
    [int]$KeepBackups = 14,
    [string]$HealthUrl = 'http://localhost:8081/api/v1/me',
    [int]$HealthTimeoutSeconds = 180
)

$ErrorActionPreference = 'Stop'
Set-Location $Dir
$env:HEARTHLEDGER_IMAGE_PREFIX = $ImagePrefix.ToLower()
$stateFile = Join-Path $Dir '.deployed-tag'
$previousTag = if (Test-Path $stateFile) { (Get-Content $stateFile -Raw).Trim() } else { $null }

function Invoke-Compose {
    & docker compose @args
    if ($LASTEXITCODE -ne 0) { throw "docker compose $($args -join ' ') failed" }
}

function Wait-Healthy {
    $deadline = (Get-Date).AddSeconds($HealthTimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        try {
            # unauthenticated API call: 401 means nginx + backend are both up
            $code = (Invoke-WebRequest -Uri $HealthUrl -UseBasicParsing -ErrorAction Stop).StatusCode
        } catch {
            $code = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 0 }
        }
        if ($code -eq 200 -or $code -eq 401) { return $true }
        Start-Sleep -Seconds 5
    }
    return $false
}

function Backup-Database {
    $running = & docker compose ps --status running --services 2>$null
    if (-not ($running -contains 'db')) { Write-Host 'db not running, skipping backup'; return }
    $dir = Join-Path $Dir 'backups'
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $file = Join-Path $dir ("hearthledger-{0}.sql.gz" -f (Get-Date -Format 'yyyyMMdd-HHmmss'))
    # cmd redirection keeps the gzip bytes intact (PowerShell pipelines would re-encode them)
    & cmd /c "docker compose exec -T db sh -c ""pg_dump -U hearthledger hearthledger | gzip"" > ""$file"""
    if ($LASTEXITCODE -ne 0) { throw 'database backup failed, aborting deploy' }
    if (-not (Test-Path $file) -or (Get-Item $file).Length -eq 0) { throw 'database backup is empty, aborting deploy' }
    Write-Host "backup written: $file"
    Get-ChildItem $dir -Filter 'hearthledger-*.sql.gz' | Sort-Object LastWriteTime -Descending | Select-Object -Skip $KeepBackups | Remove-Item
}

function Deploy-Tag([string]$t) {
    $env:HEARTHLEDGER_TAG = $t
    Invoke-Compose pull backend frontend
    Invoke-Compose up -d --no-build --remove-orphans
}

Write-Host "Deploying $ImagePrefix @ $Tag (previous: $previousTag)"
Backup-Database
Deploy-Tag $Tag
if (Wait-Healthy) {
    Set-Content -Path $stateFile -Value $Tag
    & docker image prune -f | Out-Null
    Write-Host 'Deploy OK'
    exit 0
}

Write-Host 'Health check failed.'
& docker compose logs --tail=60 backend
if ($previousTag) {
    Write-Host "Rolling back to $previousTag"
    Deploy-Tag $previousTag
    Wait-Healthy | Out-Null
}
throw "Deploy of $Tag failed (database migrations are not reverted; restore from backups\ if needed)"
