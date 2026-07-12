#Requires -Version 7.0
# Provision a local LiteRT-LM model into Hermes Android app storage for emulator/device testing.
# Streams directly into debuggable app storage via run-as; release builds use the
# app-specific external files directory scanned by HermesModelDownloadManager.
param(
    [Parameter(Mandatory = $true)]
    [string]$ModelPath,
    [string]$Serial = "",
    [string]$PackageId = "com.mobilefork.hermesagent",
    [string]$DestinationFileName = "",
    [switch]$ForceExternal
)

$ErrorActionPreference = "Stop"
if (-not (Test-Path $ModelPath)) {
    throw "Model file not found: $ModelPath"
}
if ([string]::IsNullOrWhiteSpace($DestinationFileName)) {
    $DestinationFileName = Split-Path $ModelPath -Leaf
}
if ([IO.Path]::GetFileName($DestinationFileName) -ne $DestinationFileName) {
    throw "DestinationFileName must be a file name, not a path: $DestinationFileName"
}

$adb = if ($env:ANDROID_HOME) {
    Join-Path $env:ANDROID_HOME "platform-tools\adb.exe"
} else {
    "adb"
}
$serialArgs = @()
if ($Serial) { $serialArgs = @("-s", $Serial) }

function Adb([string[]]$CommandArgs) {
    & $adb @serialArgs @CommandArgs
    if ($LASTEXITCODE -ne 0) { throw "adb failed: $($CommandArgs -join ' ')" }
}

function AdbCapture([string[]]$CommandArgs) {
    $output = & $adb @serialArgs @CommandArgs 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "adb failed: $($CommandArgs -join ' ')`n$($output -join "`n")"
    }
    return ($output -join "`n").Trim()
}

function Send-RunAsStream([string]$SourcePath, [string]$DestinationPath) {
    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $adb
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardInput = $true
    $startInfo.RedirectStandardError = $true
    foreach ($arg in $serialArgs) { [void]$startInfo.ArgumentList.Add($arg) }
    foreach ($arg in @(
        "exec-in", "run-as", $PackageId, "sh", "-c",
        'exec cat > "$1"', "hermes-model-stream", $DestinationPath
    )) {
        [void]$startInfo.ArgumentList.Add($arg)
    }

    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    try {
        if (-not $process.Start()) { throw "Unable to start adb model stream" }
        $stderrTask = $process.StandardError.ReadToEndAsync()
        $source = [IO.File]::OpenRead((Resolve-Path -LiteralPath $SourcePath).Path)
        try {
            $source.CopyTo($process.StandardInput.BaseStream, 1MB)
        } finally {
            $source.Dispose()
            $process.StandardInput.Close()
        }
        $process.WaitForExit()
        $stderr = $stderrTask.GetAwaiter().GetResult().Trim()
        if ($process.ExitCode -ne 0) {
            throw "adb run-as stream failed with exit $($process.ExitCode): $stderr"
        }
    } finally {
        $process.Dispose()
    }
}

$sourceItem = Get-Item -LiteralPath $ModelPath
$expectedBytes = $sourceItem.Length
$expectedSha256 = (Get-FileHash -LiteralPath $sourceItem.FullName -Algorithm SHA256).Hash.ToLowerInvariant()

function Assert-RemoteArtifact([string]$StorageMode, [string]$RemotePath) {
    if ($StorageMode -eq "private") {
        $sizeCommand = @("shell", "run-as", $PackageId, "wc", "-c", $RemotePath)
        $hashCommand = @("shell", "run-as", $PackageId, "sha256sum", $RemotePath)
    } else {
        $sizeCommand = @("shell", "wc", "-c", $RemotePath)
        $hashCommand = @("shell", "sha256sum", $RemotePath)
    }
    $sizeOutput = AdbCapture $sizeCommand
    $remoteBytes = [long](($sizeOutput -split '\s+')[0])
    if ($remoteBytes -ne $expectedBytes) {
        throw "Remote size mismatch for ${RemotePath}: expected $expectedBytes, got $remoteBytes"
    }
    $hashOutput = AdbCapture $hashCommand
    $remoteSha256 = (($hashOutput -split '\s+')[0]).ToLowerInvariant()
    if ($remoteSha256 -ne $expectedSha256) {
        throw "Remote SHA-256 mismatch for ${RemotePath}: expected $expectedSha256, got $remoteSha256"
    }
}

# Probe run-as before transferring bytes so a release APK never needs a
# multi-gigabyte /data/local/tmp staging copy.
$runAsOk = $false
if (-not $ForceExternal) {
    try {
        [void](AdbCapture @("shell", "run-as", $PackageId, "pwd"))
        $runAsOk = $true
    } catch {
        Write-Host "run-as unavailable (expected for a release build); using app-specific external storage."
    }
} else {
    Write-Host "ForceExternal selected; using the release-visible app-specific directory."
}

if ($runAsOk) {
    $directory = "files/hermes-home/downloads/models"
    $destination = "$directory/$DestinationFileName"
    $partial = "$destination.partial"
    Adb @("shell", "run-as", $PackageId, "mkdir", "-p", $directory)
    try {
        Write-Host "Streaming $($sourceItem.FullName) -> private app storage"
        Send-RunAsStream $sourceItem.FullName $partial
        Assert-RemoteArtifact "private" $partial
        Adb @("shell", "run-as", $PackageId, "mv", "-f", $partial, $destination)
        Assert-RemoteArtifact "private" $destination
    } catch {
        & $adb @serialArgs shell run-as $PackageId rm -f $partial 2>$null
        throw
    }
    Write-Host "Provisioned and verified private model: $destination"
} else {
    $directory = "/sdcard/Android/data/$PackageId/files/Download/models"
    $destination = "$directory/$DestinationFileName"
    $partial = "$destination.partial"
    Adb @("shell", "mkdir", "-p", $directory)
    try {
        Write-Host "Pushing $($sourceItem.FullName) -> app-specific external storage"
        Adb @("push", $sourceItem.FullName, $partial)
        Assert-RemoteArtifact "external" $partial
        Adb @("shell", "mv", "-f", $partial, $destination)
        Assert-RemoteArtifact "external" $destination
    } catch {
        & $adb @serialArgs shell rm -f $partial 2>$null
        throw
    }
    Write-Host "Provisioned and verified release-visible model: $destination"
}

Write-Host "Done. bytes=$expectedBytes sha256=$expectedSha256 runAsOk=$runAsOk"
