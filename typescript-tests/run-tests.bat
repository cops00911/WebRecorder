@echo off
set "PATH=%USERPROFILE%\tools\nodejs\node-v22.16.0-win-x64;%PATH%"
set "NODE_TLS_REJECT_UNAUTHORIZED=0"
call npx.cmd playwright test %*

echo.
echo ============================================================
echo   📊 Test Execution Complete!
echo   To view Allure Dashboard:      .\generate-allure-report.bat
echo   To view Playwright HTML Report: .\show-html-report.bat
echo ============================================================
echo.
