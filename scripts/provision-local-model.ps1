# Provision a local LiteRT-LM model into Hermes Android app storage for emulator/device testing.
# Works with debuggable builds via run-as; for release builds uses external app files path.
param(
    [Parameter(Mandatory = $true)]
    [string]$ModelPath,
    [string]$Serial = "",
    [string]$PackageId = "com.mobilefork.hermesagent",
    [string]$DestinationFileName = ""
)

$ErrorActionPreference = "Stop"
if (-not (Test-Path $ModelPath)) {
    throw "Model file not found: $ModelPath"
}
if ([string]::IsNullOrWhiteSpace($DestinationFileName)) {
    $DestinationFileName = Split-Path $ModelPath -Leaf
}

$adb = if ($env:ANDROID_HOME) {
    Join-Path $env:ANDROID_HOME "platform-tools\adb.exe"
} else {
    "adb"
}
$serialArgs = @()
if ($Serial) { $serialArgs = @("-s", $Serial) }

function Adb([string[]]$Args) {
    & $adb @serialArgs @Args
    if ($LASTEXITCODE -ne 0) { throw "adb failed: $($Args -join ' ')" }
}

$tmp = "/data/local/tmp/$DestinationFileName"
Write-Host "Pushing $ModelPath -> $tmp"
Adb @("push", $ModelPath, $tmp)

# Prefer private app files (requires debuggable package).
$runAsOk = $true
try {
    Adb @("shell", "run-as", $PackageId, "mkdir", "-p", "files/hermes-home/downloads/models")
    Adb @("shell", "run-as", $PackageId, "cp", $tmp, "files/hermes-home/downloads/models/$DestinationFileName")
    Adb @("shell", "run-as", $PackageId, "ls", "-la", "files/hermes-home/downloads/models/$DestinationFileName")
    Write-Host "Provisioned via run-as into files/hermes-home/downloads/models/"
} catch {
    $runAsOk = $false
    Write-Host "run-as failed (likely release build). Falling back to external files path..."
    $ext = "/sdcard/Android/data/$PackageId/files/Download/models"
    Adb @("shell", "mkdir", "-p", $ext)
    Adb @("shell", "cp", $tmp, "$ext/$DestinationFileName")
    Adb @("shell", "ls", "-la", "$ext/$DestinationFileName")
    Write-Host "Provisioned external model at $ext/$DestinationFileName"
    Write-Host "Import it in Settings → Local model downloads if it is not auto-detected."
}

Adb @("shell", "rm", "-f", $tmp)
Write-Host "Done. runAsOk=$runAsOk"
