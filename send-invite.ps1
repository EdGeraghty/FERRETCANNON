#!/usr/bin/env pwsh
# Send invite from @ed:geraghty.family to @ferretcannon:ferretcannon.roflcopter.wtf

# Load credentials from JSON
$credentials = Get-Content -Path "credentials.json" -Raw | ConvertFrom-Json

$edCreds = $credentials.'geraghty.family'
$edToken = $edCreds.access_token
$edHomeserver = $edCreds.homeserver
$roomId = "!ljI2RJQdNNdO7hOrzMPrACPxwQZiKxMJhCTJtMXx-hw"  # Direct room ID
$inviteeUserId = "@ferretcannon:ferretcannon.roflcopter.wtf"

Write-Host "=== Sending Invite ===" -ForegroundColor Yellow
Write-Host "From: @ed:geraghty.family" -ForegroundColor Cyan
Write-Host "To: $inviteeUserId" -ForegroundColor Cyan
Write-Host "Room ID: $roomId" -ForegroundColor Cyan
Write-Host ""

Write-Host "Sending invite..." -ForegroundColor Yellow

try {
    $inviteUrl = "$edHomeserver/_matrix/client/v3/rooms/$roomId/invite"
    Write-Host "Invite URL: $inviteUrl" -ForegroundColor Gray
    
    $inviteBody = @{
        user_id = $inviteeUserId
        reason = "LEEROY JENKINS - Testing federation join"
    } | ConvertTo-Json
    
    Write-Host "Request body: $inviteBody" -ForegroundColor Gray
    
    $response = Invoke-RestMethod -Uri $inviteUrl -Method Post `
        -Headers @{
            "Authorization" = "Bearer $edToken"
            "Content-Type" = "application/json"
        } `
        -Body $inviteBody
    
    Write-Host ""
    Write-Host "✅ Invite sent successfully!" -ForegroundColor Green
    Write-Host "Response: $($response | ConvertTo-Json -Depth 10)" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "Now run ./test-join.ps1 to accept the invite" -ForegroundColor Yellow
} catch {
    $statusCode = $_.Exception.Response.StatusCode.value__
    Write-Host "❌ Failed to send invite" -ForegroundColor Red
    Write-Host "Status Code: $statusCode" -ForegroundColor Red
    Write-Host "Error: $($_.ErrorDetails.Message)" -ForegroundColor Red
    exit 1
}
