@echo off
REM ============================================================================
REM Hermes Agent Installer for Windows (CMD wrapper)
REM ============================================================================
REM This batch file launches the PowerShell installer for users running CMD.
REM
REM Usage:
REM   curl -fsSL https://raw.githubusercontent.com/NousResearch/hermes-agent/main/scripts/install.cmd -o install.cmd && install.cmd && del install.cmd
REM
REM Or if you're already in PowerShell, use the direct command instead:
REM   irm https://raw.githubusercontent.com/NousResearch/hermes-agent/main/scripts/install.ps1 | iex
REM ============================================================================

echo.
echo  Hermes Agent Installer
echo  Launching PowerShell installer...
echo.

where pwsh.exe >nul 2>nul
if %ERRORLEVEL% EQU 0 (
    pwsh.exe -ExecutionPolicy ByPass -NoProfile -Command "irm https://raw.githubusercontent.com/NousResearch/hermes-agent/main/scripts/install.ps1 | iex"
) else (
    powershell.exe -ExecutionPolicy ByPass -NoProfile -Command "irm https://raw.githubusercontent.com/NousResearch/hermes-agent/main/scripts/install.ps1 | iex"
)

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo  Installation failed. Please try running PowerShell directly:
    echo    pwsh -ExecutionPolicy ByPass -c "irm https://raw.githubusercontent.com/NousResearch/hermes-agent/main/scripts/install.ps1 | iex"
    echo  Or, if PowerShell 7 is not installed:
    echo    powershell.exe -ExecutionPolicy ByPass -c "irm https://raw.githubusercontent.com/NousResearch/hermes-agent/main/scripts/install.ps1 | iex"
    echo.
    pause
    exit /b 1
)
