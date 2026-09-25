@echo off
set "PATH=%USERPROFILE%\tools\nodejs\node-v22.16.0-win-x64;%PATH%"
set "NODE_TLS_REJECT_UNAUTHORIZED=0"

echo.
echo ============================================================
echo   📸 Updating Visual Regression Baselines...
echo ============================================================
echo.

call npx.cmd playwright test --update-snapshots %*

echo.
echo ============================================================
echo   ✅ Visual Baselines Captured and Updated!
echo ============================================================
echo.
