#!/usr/bin/env pwsh
# Clear membership events for ferretcannon user via API

$token = Read-Host #Ed did this manually to avoid committing it
$roomId = "!ljI2RJQdNNdO7hOrzMPrACPxwQZiKxMJhCTJtMXx-hw"
$userId = "@ferretcannon:ferretcannon.roflcopter.wtf"

$url = "https://ferretcannon.roflcopter.wtf/_matrix/client/v3/rooms/$roomId/membership/$userId"

Write-Host "Clearing membership for $userId in room $roomId..."
try {
    $response = Invoke-RestMethod -Uri $url -Method Delete -Headers @{
        Authorization = "Bearer $token"
    }
    Write-Host "Success! Deleted $($response.deleted) events"
    $response | ConvertTo-Json
} catch {
    Write-Host "Error: $_"
    if ($_.ErrorDetails.Message) {
        Write-Host "Details: $($_.ErrorDetails.Message)"
    }
}
