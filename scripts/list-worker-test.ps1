$ErrorActionPreference = 'Stop'
$workerPath = Join-Path (Split-Path $PSScriptRoot -Parent) 'cloud/list-worker'
docker run --rm --mount "type=bind,source=$workerPath,target=/work" -w /work node:22 sh -c 'npm ci && npx vitest run'
if ($LASTEXITCODE -ne 0) { throw "List worker tests failed: $LASTEXITCODE" }
