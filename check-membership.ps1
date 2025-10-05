#!/usr/bin/env pwsh
# Check current membership state for @ferretcannon user

Write-Host "Enter your Element access token:" -ForegroundColor Cyan
$accessToken = Read-Host

$roomId = "!ljI2RJQdNNdO7hOrzMPrACPxwQZiKxMJhCTJtMXx-hw"

Write-Host ""
Write-Host "=== Checking WHO YOU ARE ===" -ForegroundColor Yellow
try {
    $whoamiUrl = "https://ferretcannon.roflcopter.wtf/_matrix/client/v3/account/whoami"
    $whoamiResponse = Invoke-RestMethod -Uri $whoamiUrl -Method Get `
        -Headers @{
            "Authorization" = "Bearer $accessToken"
        }
    Write-Host "You are: $($whoamiResponse.user_id)" -ForegroundColor Green
    $userId = $whoamiResponse.user_id
} catch {
    Write-Host "Failed to get user ID: $_" -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "=== Checking YOUR MEMBERSHIP STATE ===" -ForegroundColor Yellow
try {
    $stateUrl = "https://ferretcannon.roflcopter.wtf/_matrix/client/v3/rooms/$roomId/state/m.room.member/$userId"
    $membershipResponse = Invoke-RestMethod -Uri $stateUrl -Method Get `
        -Headers @{
            "Authorization" = "Bearer $accessToken"
        }
    Write-Host "Current membership: $($membershipResponse.membership)" -ForegroundColor Cyan
    Write-Host "Full response:" -ForegroundColor Gray
    Write-Host ($membershipResponse | ConvertTo-Json -Depth 10) -ForegroundColor Gray
} catch {
    if ($_.Exception.Response.StatusCode.value__ -eq 404) {
        Write-Host "No membership event found (you're not in the room)" -ForegroundColor Yellow
    } else {
        Write-Host "Failed to get membership: $_" -ForegroundColor Red
        Write-Host "Status: $($_.Exception.Response.StatusCode.value__)" -ForegroundColor Red
    }
}

Write-Host ""
Write-Host "=== Checking ALL MEMBERSHIP EVENTS FOR YOU ===" -ForegroundColor Yellow
try {
    $stateUrl = "https://ferretcannon.roflcopter.wtf/_matrix/client/v3/rooms/$roomId/state"
    $allStateResponse = Invoke-RestMethod -Uri $stateUrl -Method Get `
        -Headers @{
            "Authorization" = "Bearer $accessToken"
        }
    $memberEvents = $allStateResponse | Where-Object { $_.type -eq "m.room.member" -and $_.state_key -eq $userId }
    if ($memberEvents) {
        Write-Host "Found $(@($memberEvents).Count) membership events:" -ForegroundColor Cyan
        foreach ($event in $memberEvents) {
            Write-Host "  - Event ID: $($event.event_id)" -ForegroundColor Gray
            Write-Host "    Sender: $($event.sender)" -ForegroundColor Gray
            Write-Host "    Membership: $($event.content.membership)" -ForegroundColor Gray
            if ($event.content.reason) {
                Write-Host "    Reason: $($event.content.reason)" -ForegroundColor Gray
            }
        }
    } else {
        Write-Host "No membership events found in room state" -ForegroundColor Yellow
    }
} catch {
    Write-Host "Failed to get room state: $_" -ForegroundColor Red
}
