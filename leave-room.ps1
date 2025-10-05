#!/usr/bin/env pwsh
# Leave the room properly to reset state

Write-Host "Enter your Element access token:" -ForegroundColor Cyan
$accessToken = Read-Host

$roomId = "!ljI2RJQdNNdO7hOrzMPrACPxwQZiKxMJhCTJtMXx-hw"

Write-Host ""
Write-Host "Leaving room..." -ForegroundColor Yellow

try {
    $leaveUrl = "https://ferretcannon.roflcopter.wtf/_matrix/client/v3/rooms/$roomId/leave"
    $response = Invoke-RestMethod -Uri $leaveUrl -Method Post `
        -Headers @{
            "Authorization" = "Bearer $accessToken"
            "Content-Type" = "application/json"
        } `
        -Body "{}"
    
    Write-Host "✅ Successfully left the room!" -ForegroundColor Green
    Write-Host "Response: $($response | ConvertTo-Json -Depth 10)" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "Now ask Ed to send you a fresh invite, then try joining again with test-join.ps1" -ForegroundColor Yellow
} catch {
    $statusCode = $_.Exception.Response.StatusCode.value__
    $errorBody = $_.ErrorDetails.Message
    Write-Host "Failed to leave room: $errorBody" -ForegroundColor Red
    Write-Host "Status Code: $statusCode" -ForegroundColor Red
    
    if ($statusCode -eq 403) {
        Write-Host ""
        Write-Host "403 Forbidden suggests you're not actually in the room (split-brain state)." -ForegroundColor Yellow
        Write-Host "The server thinks you're joined but you don't have access." -ForegroundColor Yellow
        Write-Host ""
        Write-Host "This requires fixing the database directly or clearing the broken membership state." -ForegroundColor Red
    }
}
