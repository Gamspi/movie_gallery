# Deploys cloud/list-worker with wrangler in Docker (node:22).
#   ./scripts/list-worker-deploy.ps1           remote D1 migrations + wrangler deploy
#   ./scripts/list-worker-deploy.ps1 -Secrets  upload worker secrets from .env and google-sa.json (stdin, never printed)
#   ./scripts/list-worker-deploy.ps1 -DryRun   bundle only (wrangler deploy --dry-run), nothing is sent
# CLOUDFLARE_API_TOKEN / CLOUDFLARE_ACCOUNT_ID come from .env and reach the container as inherited env vars.
param([switch]$Secrets, [switch]$DryRun)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$workerPath = Join-Path $root 'cloud/list-worker'
$config = @{}
foreach ($line in [IO.File]::ReadAllLines((Join-Path $root '.env'))) {
    if ($line -match '^\s*([A-Z0-9_]+)\s*=\s*(.*?)\s*$') { $config[$Matches[1]] = $Matches[2].Trim('"', "'") }
}
if (!$DryRun -and (!$config.CLOUDFLARE_API_TOKEN -or !$config.CLOUDFLARE_ACCOUNT_ID)) {
    throw 'Missing CLOUDFLARE_API_TOKEN or CLOUDFLARE_ACCOUNT_ID in .env'
}
$mount = "type=bind,source=$workerPath,target=/work"
function Invoke-Wrangler([string]$command, [string]$stdin = $null) {
    $script = "npm ci --silent --no-audit --no-fund >/dev/null 2>&1 && npx wrangler $command"
    if ($null -ne $stdin) {
        $stdin | docker run --rm -i -e CLOUDFLARE_API_TOKEN -e CLOUDFLARE_ACCOUNT_ID --mount $mount -w /work node:22 sh -c $script
    } else {
        docker run --rm -e CLOUDFLARE_API_TOKEN -e CLOUDFLARE_ACCOUNT_ID --mount $mount -w /work node:22 sh -c $script
    }
    if ($LASTEXITCODE -ne 0) { throw "wrangler failed: $($command.Split(' ')[0..1] -join ' ')" }
}
$env:CLOUDFLARE_API_TOKEN = $config.CLOUDFLARE_API_TOKEN
$env:CLOUDFLARE_ACCOUNT_ID = $config.CLOUDFLARE_ACCOUNT_ID
$previousEncoding = $OutputEncoding
try {
    if ($DryRun) { Invoke-Wrangler 'deploy --dry-run --outdir /tmp/vg-list-dry'; return }
    if ($Secrets) {
        $values = [ordered]@{}
        foreach ($name in 'LIST_KEY', 'TMDB_API_KEY', 'POISKKINO_API_KEY', 'TG_BOT_TOKEN', 'TG_CHAT_ID', 'GOOGLE_SHEET_ID') {
            if ($config[$name]) { $values[$name] = $config[$name] } else { Write-Output "skip (empty in .env): $name" }
        }
        $saPath = Join-Path $root 'google-sa.json'
        if (Test-Path $saPath) { $values['GOOGLE_SA_JSON'] = [IO.File]::ReadAllText($saPath).Trim() } else { Write-Output 'skip (no file): GOOGLE_SA_JSON' }
        if ($values.Count -eq 0) { throw 'No secrets to upload' }
        $OutputEncoding = New-Object Text.UTF8Encoding $false
        Invoke-Wrangler 'secret bulk' ($values | ConvertTo-Json -Compress)
        Write-Output "uploaded: $($values.Keys -join ', ')"
        return
    }
    # A base created from schema.sql in the dashboard has no d1_migrations table:
    # record 0001/0002 as applied once (only if titles exists), so that only new migrations run.
    $baselinePath = Join-Path $workerPath '.wrangler/baseline.sql'
    New-Item -ItemType Directory -Force (Split-Path $baselinePath) | Out-Null
    [IO.File]::WriteAllText($baselinePath, (@(
        'CREATE TABLE IF NOT EXISTS d1_migrations(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT UNIQUE, applied_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP);',
        "INSERT OR IGNORE INTO d1_migrations(name) SELECT '0001_init.sql' WHERE EXISTS (SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'titles');",
        "INSERT OR IGNORE INTO d1_migrations(name) SELECT '0002_client_time.sql' WHERE EXISTS (SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'titles');"
    ) -join "`n"), (New-Object Text.UTF8Encoding $false))
    Invoke-Wrangler 'd1 execute vg-list --remote --yes --file .wrangler/baseline.sql'
    Invoke-Wrangler 'd1 migrations apply vg-list --remote'
    Invoke-Wrangler 'deploy'
} finally {
    $OutputEncoding = $previousEncoding
    Remove-Item Env:CLOUDFLARE_API_TOKEN, Env:CLOUDFLARE_ACCOUNT_ID -ErrorAction SilentlyContinue
}
