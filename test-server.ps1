# Quick test script for FERRETCANNON server
# Run this to quickly test if the server is responding

param(
    [int]$Timeout = 30
)

Write-Host "Testing FERRETCANNON Matrix Server..." -ForegroundColor Green

$startTime = Get-Date
$endTime = $startTime.AddSeconds($Timeout)

while ((Get-Date) -lt $endTime) {
    try {
        $response = Invoke-WebRequest -Uri "http://localhost:8080/_matrix/client/v3/login" -Method GET -TimeoutSec 5 -ErrorAction Stop
        if ($response.StatusCode -eq 200) {
            Write-Host "✅ Server is responding!" -ForegroundColor Green
            Write-Host "Status: $($response.StatusCode)" -ForegroundColor Green
            Write-Host "Response: $($response.Content)" -ForegroundColor Gray
            exit 0
        }
    } catch {
        Write-Host "⏳ Server not ready yet... ($((Get-Date) - $startTime).TotalSeconds.ToString("0"))s)" -ForegroundColor Yellow
        Start-Sleep -Seconds 2
    }
}

Write-Host "❌ Server test timed out after $Timeout seconds" -ForegroundColor Red
exit 1
