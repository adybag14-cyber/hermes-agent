# Emulator validation for Hermes v0.13.134
# Captures screenshots and exercises Device sandbox UI (Debian + Alpine) + chat tool display.

$ErrorActionPreference = "Stop"
$RepoRoot = Split-Path -Parent $PSScriptRoot
$OutDir = Join-Path $RepoRoot "verification-screenshots/v0.13.134"
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$env:ANDROID_SDK_ROOT = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else {
    "C:\Users\Ady\Documents\Codex\2026-05-02\c-users-ady-downloads-hermes-android\_android_sdk"
}
$Serial = "emulator-5554"
$Harness = Join-Path $RepoRoot "scripts/android_visual_harness.py"
$Python = if (Get-Command python -ErrorAction SilentlyContinue) { "python" } else { "py" }

function Invoke-Harness {
    param([string[]]$HarnessArgs)
    & $Python $Harness --serial $Serial @HarnessArgs
    if ($LASTEXITCODE -ne 0) { throw "Harness failed: $($HarnessArgs -join ' ')" }
}

function Tap-Text {
    param([string]$Label)
    $xmlPath = Join-Path $OutDir "ui-temp.xml"
    Invoke-Harness @("dump-ui", "--out", $xmlPath)
    $xml = Get-Content -Raw -LiteralPath $xmlPath
    $escaped = [regex]::Escape($Label)

    $rowPattern = '<node[^>]*clickable="true"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*>[\s\S]*?text="' + $escaped + '"'
    if ($xml -match $rowPattern) {
        $x = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
        $y = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
        Invoke-Harness @("tap", "$x", "$y")
        Start-Sleep -Seconds 2
        return
    }

    if ($xml -notmatch "text=`"$escaped`"" -and $xml -notmatch "content-desc=`"$escaped`"") {
        throw "UI text not found: $Label"
    }
    $pattern = "(?:text|content-desc)=`"$escaped`"[^>]*bounds=`"\[(\d+),(\d+)\]\[(\d+),(\d+)\]`""
    if ($xml -match $pattern) {
        $x = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
        $y = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
        Invoke-Harness @("tap", "$x", "$y")
        Start-Sleep -Seconds 2
        return
    }
    throw "Could not parse bounds for: $Label"
}

function Test-NavigationMenuOpen {
    $xmlPath = Join-Path $OutDir "ui-menu-check.xml"
    Invoke-Harness @("dump-ui", "--out", $xmlPath)
    $xml = Get-Content -Raw -LiteralPath $xmlPath
    return ($xml -match 'text="Device"') -and ($xml -match 'text="Provider Portal"')
}

function Ensure-NavigationMenuOpen {
    if (-not (Test-NavigationMenuOpen)) {
        Open-NavigationMenu
        Start-Sleep -Seconds 1
    }
}

function Open-NavigationMenu {
    $xmlPath = Join-Path $OutDir "ui-temp.xml"
    Invoke-Harness @("dump-ui", "--out", $xmlPath)
    $xml = Get-Content -Raw -LiteralPath $xmlPath
    if ($xml -match 'content-desc="Open navigation menu"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') {
        $x = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
        $y = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
        Invoke-Harness @("tap", "$x", "$y")
    } else {
        Invoke-Harness @("tap", "96", "241")
    }
    Start-Sleep -Seconds 2
}

Write-Host "Launching Hermes..."
Invoke-Harness launch
Start-Sleep -Seconds 12
Invoke-Harness @("screenshot", "--out", (Join-Path $OutDir "01-chat-home.png"))

Write-Host "Opening navigation menu..."
Ensure-NavigationMenuOpen
Invoke-Harness @("screenshot", "--out", (Join-Path $OutDir "02-navigation-menu.png"))

Write-Host "Opening Device screen..."
Ensure-NavigationMenuOpen
Tap-Text "Device"
Start-Sleep -Seconds 6
Invoke-Harness @("screenshot", "--out", (Join-Path $OutDir "03-device-top.png"))

$cardXml = ""
for ($scroll = 0; $scroll -lt 4; $scroll++) {
    Invoke-Harness @("swipe", "540", "1800", "540", "600", "--duration-ms", "500")
    Start-Sleep -Seconds 2
    Invoke-Harness @("screenshot", "--out", (Join-Path $OutDir "04-device-linux-sandbox-card.png"))
    Invoke-Harness @("dump-ui", "--out", (Join-Path $OutDir "04-device-linux-sandbox-card.xml"))
    $cardXml = Get-Content -Raw (Join-Path $OutDir "04-device-linux-sandbox-card.xml")
    if ($cardXml -match "Linux sandbox" -and $cardXml -match "Deploy Alpine") {
        break
    }
}
if ($cardXml -notmatch "Linux sandbox") { throw "Linux sandbox card not visible on Device screen" }
if ($cardXml -notmatch "Deploy Alpine") { throw "Deploy Alpine button not visible on Device screen" }
Write-Host "Linux sandbox card with Deploy Alpine present."

Write-Host "Tapping Deploy Alpine..."
Tap-Text "Deploy Alpine"
Start-Sleep -Seconds 8
Invoke-Harness @("screenshot", "--out", (Join-Path $OutDir "05-alpine-deploy-started.png"))

Write-Host "Returning to Hermes chat..."
Ensure-NavigationMenuOpen
Tap-Text "Hermes Fork"
Start-Sleep -Seconds 2
Invoke-Harness @("screenshot", "--out", (Join-Path $OutDir "06-chat-ready.png"))

Write-Host "Sending Alpine sandbox run prompt..."
Tap-Text "Message Hermes Fork"
Start-Sleep -Seconds 1
Invoke-Harness @("text", "Use mcp_run_in_proot to run: uname -a. Then use linux_sandbox_tool action=status.")
Invoke-Harness @("keyevent", "66")
Start-Sleep -Seconds 60
Invoke-Harness @("screenshot", "--out", (Join-Path $OutDir "07-chat-alpine-tool-run.png"))

Write-Host "Sending memory tool alias prompt..."
Tap-Text "Message Hermes Fork"
Invoke-Harness @("text", "Use memory_add content='violet-714 alpine validation sentinel' then memory_search query=violet-714.")
Invoke-Harness @("keyevent", "66")
Start-Sleep -Seconds 60
Invoke-Harness @("screenshot", "--out", (Join-Path $OutDir "08-chat-memory-tool-display.png"))

Write-Host "Validation screenshots written to $OutDir"