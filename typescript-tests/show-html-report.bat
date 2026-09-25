@echo off
set "PATH=%USERPROFILE%\tools\nodejs\node-v22.16.0-win-x64;%PATH%"
set "NODE_TLS_REJECT_UNAUTHORIZED=0"

echo.
echo ============================================================
echo   Opening Playwright HTML Report...
echo ============================================================
echo.

call .\node_modules\.bin\playwright.cmd show-report reports/html-report
