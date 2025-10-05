#!/usr/bin/env pwsh
# Reset membership back to invite state by deleting the broken join event

$roomId = "!ljI2RJQdNNdO7hOrzMPrACPxwQZiKxMJhCTJtMXx-hw"
$userId = "@ferretcannon:ferretcannon.roflcopter.wtf"
$accessToken = "test_access_token"

Write-Host "Resetting membership for $userId in room $roomId..." -ForegroundColor Yellow

# Try to leave the room first (this should work since we're "joined")
try {
    $leaveUrl = "https://ferretcannon.roflcopter.wtf/_matrix/client/v3/rooms/$roomId/leave"
    $response = Invoke-RestMethod -Uri $leaveUrl -Method Post `
        -Headers @{
            "Authorization" = "Bearer $accessToken"
            "Content-Type" = "application/json"
        } `
        -Body "{}"
    
    Write-Host "Successfully left the room!" -ForegroundColor Green
    Write-Host "Response: $($response | ConvertTo-Json -Depth 10)" -ForegroundColor Cyan
} catch {
    Write-Host "Failed to leave room: $_" -ForegroundColor Red
    Write-Host "Status Code: $($_.Exception.Response.StatusCode.value__)" -ForegroundColor Red
    Write-Host "Response: $($_.ErrorDetails.Message)" -ForegroundColor Red
}

Write-Host ""
Write-Host "Now your membership should be 'leave'. Ask Ed to re-invite you!" -ForegroundColor Green
