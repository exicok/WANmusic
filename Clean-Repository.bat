@echo off
setlocal

cd /d "%~dp0"

set "SCRIPT=%~dp0scripts\Clean-Repository.ps1"
if not exist "%SCRIPT%" (
    echo [ERROR] Cleanup script not found:
    echo %SCRIPT%
    pause
    exit /b 1
)

if /i "%~1"=="preview" (
    powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT%" -WhatIf
) else if /i "%~1"=="untrack" (
    powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT%" -Untrack
) else if /i "%~1"=="all" (
    powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT%" -Untrack -IncludeLocalConfig
) else (
    powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT%"
)

set "EXIT_CODE=%ERRORLEVEL%"
if not "%EXIT_CODE%"=="0" (
    echo.
    echo [ERROR] Cleanup failed with exit code %EXIT_CODE%.
) else (
    echo.
    echo Cleanup finished.
)

pause
exit /b %EXIT_CODE%
