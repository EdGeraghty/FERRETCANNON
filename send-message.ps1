#!/usr/bin/env pwsh
# Send a test message to the room

$token = "JkNxbPE3UqlPsKDtI-PGKhugFfPVyZHh1ciDQ1WZ7GE"
$roomId = "!ljI2RJQdNNdO7hOrzMPrACPxwQZiKxMJhCTJtMXx-hw"

$txnId = [guid]::NewGuid().ToString()
$url = "https://ferretcannon.roflcopter.wtf/_matrix/client/v3/rooms/$roomId/send/m.room.message/$txnId"

$body = @{
    msgtype = "m.text"
    body = "🎉 Federation join successful! ferretcannon can now communicate with Ed's room via Matrix federation!"
} | ConvertTo-Json

Write-Host "Sending message to room $roomId..."
try {
    $response = Invoke-RestMethod -Uri $url -Method Put -Headers @{
        Authorization = "Bearer $token"
        "Content-Type" = "application/json"
    } -Body $body
    
    Write-Host "✅ Message sent successfully!"
    Write-Host "Event ID: $($response.event_id)"
} catch {
    Write-Host "❌ Error sending message: $_"
    if ($_.ErrorDetails.Message) {
        Write-Host "Details: $($_.ErrorDetails.Message)"
    }
}
