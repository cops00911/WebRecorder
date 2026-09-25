@echo off
set "NODE_TLS_REJECT_UNAUTHORIZED=0"
echo ============================================================
echo   🎥 Starting Web Recorder (Java + TypeScript Generator)
echo ============================================================
call mvn exec:java %*
