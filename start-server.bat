@echo off
echo Starting FERRETCANNON Matrix Server...
echo.

REM Kill any existing Java processes
echo Stopping existing Java processes...
powershell -Command "Get-Process | Where-Object { $_.ProcessName -like '*java*' -or $_.ProcessName -like '*gradle*' } | Stop-Process -Force -ErrorAction SilentlyContinue"
timeout /t 2 /nobreak > nul

REM Start the server
echo Starting server...
gradle run --console=plain

echo.
echo Server stopped.
pause
