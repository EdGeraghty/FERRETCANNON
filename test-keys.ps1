Write-Host "Testing server key endpoint..." -ForegroundColor Cyan
try {
    $response = Invoke-RestMethod -Uri "https://ferretcannon.roflcopter.wtf/_matrix/key/v2/server" -Method Get
    Write-Host "Success! Server keys endpoint is working:" -ForegroundColor Green
    $response | ConvertTo-Json -Depth 10
} catch {
    Write-Host "ERROR:" -ForegroundColor Red
    Write-Host $_.Exception.Message
    if ($_.ErrorDetails) {
        Write-Host $_.ErrorDetails.Message
    }
}
