# One-time setup of the instant-alerts server on Cloudflare (free), from Windows PowerShell.
# Run "Install stock alerts.cmd" (or: powershell -ExecutionPolicy Bypass -File setup.ps1) next to fcm-key.json.
# Needs Node.js. Opens the browser once to log in to Cloudflare (the same account as Holmstead).
param([string]$Key = "$PSScriptRoot\fcm-key.json")
$ErrorActionPreference = "Continue"
Set-Location $PSScriptRoot
Start-Transcript -Path "$PSScriptRoot\setup.log" -Force | Out-Null
Write-Host "Node: $(node --version)"
if (-not (Test-Path $Key)) { Write-Host "Firebase key file not found: $Key" -ForegroundColor Red; Stop-Transcript; return }

# Install Cloudflare's tool once in this folder (npx kept reinstalling it and tripping over locked files).
$W = "$PSScriptRoot\node_modules\.bin\wrangler.cmd"
if (-not (Test-Path $W)) {
  Write-Host "0/4 Installing Cloudflare's tool (wrangler), about a minute..."
  cmd /c "npm install wrangler@4 --no-save --no-fund --no-audit --loglevel=error 2>&1"
}
if (-not (Test-Path $W)) { Write-Host "Couldn't install wrangler." -ForegroundColor Red; Stop-Transcript; return }
function W { param([Parameter(ValueFromRemainingArguments = $true)]$a) $out = cmd /c "`"$W`" $($a -join ' ') 2>&1"; ($out | Out-String) }

Write-Host "1/4 Checking the Cloudflare login..."
$who = W whoami
Write-Host $who
if ($who -notmatch "associated with the email|Account Name|Account ID") {
  Write-Host "Please log in to Cloudflare and click 'Allow' in the browser window that opens." -ForegroundColor Yellow
  cmd /c "`"$W`" login"
  $who = W whoami; Write-Host $who
  if ($who -notmatch "associated with the email|Account Name|Account ID") { Write-Host "Not logged in to Cloudflare - run this again." -ForegroundColor Red; Stop-Transcript; return }
}

Write-Host "2/4 Creating the storage for alert state..."
$toml = Get-Content wrangler.toml -Raw
if ($toml -match "REPLACE_WITH_KV_ID") {
  $list = W kv namespace list
  $id = $null
  if ($list -match '"id":\s*"([0-9a-f]{32})",\s*"title":\s*"(stock-alerts-)?STATE"') { $id = $Matches[1] }
  if (-not $id) {
    $out = W kv namespace create STATE
    Write-Host $out
    if ($out -match '"?id"?\s*[=:]\s*"([0-9a-f]{32})"') { $id = $Matches[1] }
  }
  if (-not $id) { Write-Host "Couldn't create the storage (see above)." -ForegroundColor Red; Stop-Transcript; return }
  ($toml -replace "REPLACE_WITH_KV_ID", $id) | Set-Content wrangler.toml -NoNewline
  Write-Host "Storage id: $id"
}

Write-Host "3/4 Deploying..."
$dep = W deploy
Write-Host $dep

Write-Host "4/4 Storing the Firebase key as a secret..."
cmd /c "type `"$Key`" | `"$W`" secret put FCM_SERVICE_ACCOUNT 2>&1"

if ($dep -match '(https://stock-alerts\.[a-z0-9-]+\.workers\.dev)') {
  Write-Host ""
  Write-Host "Done! Server URL: $($Matches[1])" -ForegroundColor Green
} else { Write-Host "Deploy didn't print the address - see above." -ForegroundColor Red }
Stop-Transcript | Out-Null
