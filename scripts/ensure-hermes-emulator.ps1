# Ensure HermesX86Api35 is online via detached launch (avoids agent 10h max_runtime kill).
$ErrorActionPreference = "Stop"
$RepoRoot = Split-Path -Parent $PSScriptRoot
if (-not $env:ANDROID_SDK_ROOT -and -not $env:ANDROID_HOME) {
    $env:ANDROID_SDK_ROOT = "C:\Users\Ady\Documents\Codex\2026-05-02\c-users-ady-downloads-hermes-android\_android_sdk"
}
$Python = if (Get-Command python -ErrorAction SilentlyContinue) { "python" } else { "py" }
& $Python (Join-Path $RepoRoot "scripts\emulator_lifecycle.py") @args
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }