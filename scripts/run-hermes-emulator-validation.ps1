# Foreground validation pipeline: ensure detached emulator, install APK, run harness, exit.
# Do NOT run emulator.exe as an agent background shell (10h max_runtime cap).
param(
    [string]$Serial = "emulator-5554",
    [ValidateSet("chat-ui", "full", "device-only")]
    [string]$Suite = "chat-ui",
    [string]$OutVersion = "v0.13.135",
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$RepoRoot = Split-Path -Parent $PSScriptRoot
if (-not $env:ANDROID_SDK_ROOT -and -not $env:ANDROID_HOME) {
    $env:ANDROID_SDK_ROOT = "C:\Users\Ady\Documents\Codex\2026-05-02\c-users-ady-downloads-hermes-android\_android_sdk"
}
$Python = if (Get-Command python -ErrorAction SilentlyContinue) { "python" } else { "py" }
$Apk = Join-Path $RepoRoot "android\app\build\outputs\apk\debug\app-debug.apk"

if (-not $SkipBuild -and -not (Test-Path -LiteralPath $Apk)) {
    Write-Host "Building debug APK..."
    Push-Location (Join-Path $RepoRoot "android")
    try {
        .\gradlew :app:assembleDebug --no-daemon -q
    } finally {
        Pop-Location
    }
    if (-not (Test-Path -LiteralPath $Apk)) {
        throw "APK build failed: $Apk"
    }
}

Write-Host "Ensuring emulator + APK (detached launch, bounded wait)..."
& $Python -u (Join-Path $RepoRoot "scripts\emulator_lifecycle.py") --serial $Serial --install-apk --apk $Apk
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$OutDir = Join-Path $RepoRoot "verification-screenshots\$OutVersion"
switch ($Suite) {
    "chat-ui" {
        & $Python -u (Join-Path $RepoRoot "scripts\emulator-chat-ui-validation.py") `
            --serial $Serial `
            --out-dir (Join-Path $OutDir "chat-ui") `
            --no-ensure-emulator
    }
    "device-only" {
        & $Python -u (Join-Path $RepoRoot "scripts\emulator-v013134-validation.py") `
            --serial $Serial `
            --out-dir $OutDir `
            --skip-chat `
            --no-ensure-emulator
    }
    "full" {
        & $Python -u (Join-Path $RepoRoot "scripts\emulator-v013134-validation.py") `
            --serial $Serial `
            --out-dir $OutDir `
            --no-ensure-emulator
    }
}
if ($LASTEXITCODE -ne 0) {
    Write-Host "Validation failed with exit code $LASTEXITCODE"
    exit $LASTEXITCODE
}
Write-Host "Validation completed successfully."