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
    # Run the server in background
    $job = Start-Job -ScriptBlock {
        Set-Location $using:PWD
        & "gradle" "run" "--console=plain" 2>&1 | Out-File -FilePath "server_output.log" -Append
    }

    # Wait a moment for server to start
    Start-Sleep -Seconds 5

    # Check if server is running
    $javaProcess = Get-Process -Name "java" -ErrorAction SilentlyContinue
    if ($javaProcess) {
        Write-Host "Server started successfully (PID: $($javaProcess.Id))" -ForegroundColor Green
        Write-Host "Server output is being logged to server_output.log" -ForegroundColor Cyan
        Write-Host "Use 'Get-Job' to check job status or 'Stop-Job' to stop the server" -ForegroundColor Cyan
    } else {
        Write-Host "Warning: Java process not found after startup" -ForegroundColor Yellow
    }

    if (-not $NoPrompt) {
        Read-Host "Press Enter to exit (server will continue running)"
    }
} catch {
    Write-Host "Error starting server: $($_.Exception.Message)" -ForegroundColor Red
    if (-not $NoPrompt) {
        Read-Host "Press Enter to exit"
    }
}
