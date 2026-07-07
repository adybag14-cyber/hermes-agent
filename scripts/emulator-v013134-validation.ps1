# Emulator validation for Hermes v0.13.134 (Python harness driver)
$ErrorActionPreference = "Stop"
$RepoRoot = Split-Path -Parent $PSScriptRoot
$env:ANDROID_SDK_ROOT = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else {
    "C:\Users\Ady\Documents\Codex\2026-05-02\c-users-ady-downloads-hermes-android\_android_sdk"
}
$Python = if (Get-Command python -ErrorAction SilentlyContinue) { "python" } else { "py" }
& $Python (Join-Path $RepoRoot "scripts\emulator-v013134-validation.py") @args
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }