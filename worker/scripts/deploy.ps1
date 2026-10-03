# Puts the APKs of the latest release on the download page and deploys the worker.
# Needs `npx wrangler login` and `gh auth login` once. Run from anywhere: .\worker\scripts\deploy.ps1
param([string]$Repo = "mertbek/ShareScreen")
$ErrorActionPreference = "Stop"
$worker = Resolve-Path (Join-Path $PSScriptRoot "..")
$download = Join-Path ([IO.Path]::GetTempPath()) "sharescreen-release-apks"

if (Test-Path $download) { Remove-Item $download -Recurse -Force }
& gh release download --repo $Repo --pattern "ShareScreen-v*.apk" --dir $download
if ($LASTEXITCODE -ne 0) { throw "Could not download the release APKs" }
$lite = Get-ChildItem $download -Filter "*-lite.apk" | Select-Object -First 1
$full = Get-ChildItem $download -Filter "*.apk" | Where-Object { $_.Name -notlike "*-lite.apk" } | Select-Object -First 1
if (-not $lite -or -not $full) { throw "The latest release has no full and lite APK" }
Copy-Item $full.FullName (Join-Path $worker "public\ShareScreen.apk") -Force
Copy-Item $lite.FullName (Join-Path $worker "public\ShareScreen-lite.apk") -Force

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
