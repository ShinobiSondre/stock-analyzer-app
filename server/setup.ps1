# One-time setup of the instant-alerts server on Cloudflare (free), from Windows PowerShell.
#   cd stock-analyzer-app\server
#   powershell -ExecutionPolicy Bypass -File setup.ps1        (uses fcm-key.json next to this script)
# Needs Node.js. Opens the browser once to log in to Cloudflare (the same account as Holmstead).
param([string]$Key = "$PSScriptRoot\fcm-key.json")
$ErrorActionPreference = "Continue"
Set-Location $PSScriptRoot
Start-Transcript -Path "$PSScriptRoot\setup.log" -Force | Out-Null
Write-Host "Node: $(node --version 2>&1)"
if (-not (Test-Path $Key)) { throw "Firebase key file not found: $Key" }

Write-Host "1/4 Logging in to Cloudflare (a browser window may open)..."
$who = (npx --yes wrangler@latest whoami 2>&1) -join "`n"
if ($who -match "not authenticated|Failed|wrangler login") {
  Write-Host "Please click 'Allow' in the browser window that opens." -ForegroundColor Yellow
  npx --yes wrangler@latest login
}

Write-Host "2/4 Creating the storage for alert state..."
$toml = Get-Content wrangler.toml -Raw
if ($toml -match "REPLACE_WITH_KV_ID") {
  $out = (npx --yes wrangler@latest kv namespace create STATE 2>&1) -join "`n"
  if ($out -notmatch '"?id"?\s*[=:]\s*"([0-9a-f]{32})"') {
    $list = (npx --yes wrangler@latest kv namespace list) -join "`n"
    if ($list -match '"id":\s*"([0-9a-f]{32})",\s*"title":\s*"[^"]*STATE') { $id = $Matches[1] } else { Write-Host $out; throw "Couldn't create the KV namespace" }
  } else { $id = $Matches[1] }
  ($toml -replace "REPLACE_WITH_KV_ID", $id) | Set-Content wrangler.toml -NoNewline
}

Write-Host "3/4 Storing the Firebase key as a secret..."
Get-Content $Key -Raw | npx --yes wrangler@latest secret put FCM_SERVICE_ACCOUNT

Write-Host "4/4 Deploying..."
$dep = (npx --yes wrangler@latest deploy 2>&1) -join "`n"
Write-Host $dep
if ($dep -match '(https://stock-alerts\.[a-z0-9-]+\.workers\.dev)') {
  Write-Host ""
  Write-Host "Done! Server URL: $($Matches[1])" -ForegroundColor Green
  Write-Host "Send this URL and app\google-services.json to Claude."
} else { Write-Host "Deploy finished - look for the workers.dev URL above." }
Stop-Transcript | Out-Null
