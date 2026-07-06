# Emulator validation for Hermes v0.13.133
# Captures screenshots and exercises Device sandbox UI + chat prompts.

$ErrorActionPreference = "Stop"
$RepoRoot = Split-Path -Parent $PSScriptRoot
$OutDir = Join-Path $RepoRoot "verification-screenshots/v0.13.133"
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

    # Prefer a clickable parent row that contains the target label text.
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

Write-Host "Launching Hermes..."
Invoke-Harness launch
Start-Sleep -Seconds 12

# Open drawer via known menu button coordinates when content-desc is unavailable.
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
Invoke-Harness @("screenshot", "--out", (Join-Path $OutDir "01-chat-home.png"))

Write-Host "Opening navigation menu..."
Open-NavigationMenu
Invoke-Harness @("screenshot", "--out", (Join-Path $OutDir "02-navigation-menu.png"))

Write-Host "Opening Device screen..."
Tap-Text "Device"
Start-Sleep -Seconds 4
Start-Sleep -Seconds 3
Invoke-Harness @("screenshot", "--out", (Join-Path $OutDir "03-device-top.png"))

# Scroll down to Linux sandbox card
Invoke-Harness @("swipe", "540", "1800", "540", "600", "--duration-ms", "500")
Start-Sleep -Seconds 2
Invoke-Harness @("screenshot", "--out", (Join-Path $OutDir "04-device-linux-sandbox-card.png"))
Invoke-Harness @("dump-ui", "--out", (Join-Path $OutDir "04-device-linux-sandbox-card.xml"))

if ((Get-Content -Raw (Join-Path $OutDir "04-device-linux-sandbox-card.xml")) -match "Linux sandbox") {
    Write-Host "Linux sandbox card present."
} else {
    throw "Linux sandbox card not visible on Device screen"
}

Write-Host "Tapping Deploy..."
Tap-Text "Deploy"
Start-Sleep -Seconds 5
Invoke-Harness @("screenshot", "--out", (Join-Path $OutDir "05-sandbox-deploy-started.png"))

Write-Host "Returning to Hermes chat..."
Open-NavigationMenu
Tap-Text "Hermes Fork"
Start-Sleep -Seconds 2
Invoke-Harness @("screenshot", "--out", (Join-Path $OutDir "06-chat-ready.png"))

Write-Host "Sending sandbox status prompt..."
Tap-Text "Message Hermes Fork"
Start-Sleep -Seconds 1
Invoke-Harness @("text", "Use linux_sandbox_tool action=status and report installed sandboxes and mirror_profiles.")
Invoke-Harness @("keyevent", "66")
Start-Sleep -Seconds 45
Invoke-Harness @("screenshot", "--out", (Join-Path $OutDir "07-chat-sandbox-status.png"))

Write-Host "Sending memory validation prompt..."
Tap-Text "Message Hermes Fork"
Invoke-Harness @("text", "Use hy_memory_tool action=hy_memory_retain content='violet-714 emulator validation sentinel' then hy_memory_recall query=violet-714.")
Invoke-Harness @("keyevent", "66")
Start-Sleep -Seconds 60
Invoke-Harness @("screenshot", "--out", (Join-Path $OutDir "08-chat-memory-validation.png"))

Write-Host "Validation screenshots written to $OutDir"