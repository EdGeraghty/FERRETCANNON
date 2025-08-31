# FERRETCANNON Server Auto-Start Script
# This script automatically starts the Matrix server without manual intervention

param(
    [switch]$NoPrompt
)

Write-Host "FERRETCANNON Matrix Server Auto-Start" -ForegroundColor Green
Write-Host "====================================" -ForegroundColor Green

# Stop any existing processes
Write-Host "Stopping existing Java/Gradle processes..." -NoNewline
Get-Process | Where-Object { $_.ProcessName -like "*java*" -or $_.ProcessName -like "*gradle*" } | Stop-Process -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 2
Write-Host " Done" -ForegroundColor Green

# Clean any leftover files if needed
if (Test-Path "server_output.log") {
    Remove-Item "server_output.log" -ErrorAction SilentlyContinue
}

# Start the server
Write-Host "Starting FERRETCANNON Matrix Server..." -ForegroundColor Yellow
Write-Host "Server will be available at http://localhost:8080" -ForegroundColor Cyan
Write-Host ""

try {
    & "gradle" "run" "--console=plain"
} catch {
    Write-Host "Error starting server: $($_.Exception.Message)" -ForegroundColor Red
    if (-not $NoPrompt) {
        Read-Host "Press Enter to exit"
    }
}
