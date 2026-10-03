# Tests the worker and deploys it. Needs `npx wrangler login` once.
# Run from anywhere: .\worker\scripts\deploy.ps1
$ErrorActionPreference = "Stop"
$worker = Resolve-Path (Join-Path $PSScriptRoot "..")

Push-Location $worker
try {
    npm ci --no-audit --no-fund
    if ($LASTEXITCODE -ne 0) { throw "npm ci failed" }
    npx tsc --noEmit
    if ($LASTEXITCODE -ne 0) { throw "Type check failed" }
    npx vitest run
    if ($LASTEXITCODE -ne 0) { throw "Worker tests failed" }
    npx wrangler deploy
} finally {
    Pop-Location
}
