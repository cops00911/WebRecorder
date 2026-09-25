@echo off
set "PATH=%USERPROFILE%\tools\nodejs\node-v22.16.0-win-x64;%PATH%"
set "NODE_TLS_REJECT_UNAUTHORIZED=0"

echo.
echo ============================================================
echo   Generating Executive Allure Report...
echo ============================================================
echo.

call .\node_modules\.bin\allure.cmd generate allure-results --clean -o reports/allure-report

if %ERRORLEVEL% EQU 0 (
    echo.
    echo ✅ Allure Report generated at reports/allure-report
    echo 🚀 Opening Allure Dashboard in your browser...
    call .\node_modules\.bin\allure.cmd open reports/allure-report
) else (
    echo ❌ Failed to generate Allure Report. Did you run tests first?
)
