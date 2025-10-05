#!/usr/bin/env pwsh
# Have Ed send a message from his server to test bidirectional federation

$credentials = Get-Content credentials.json | ConvertFrom-Json
$edCreds = $credentials."geraghty.family"

$roomId = "!ljI2RJQdNNdO7hOrzMPrACPxwQZiKxMJhCTJtMXx-hw"
$txnId = [guid]::NewGuid().ToString()
$url = "$($edCreds.homeserver)/_matrix/client/v3/rooms/$roomId/send/m.room.message/$txnId"

$body = @{
    msgtype = "m.text"
    body = "Testing federation - message from Ed's Synapse server to ferretcannon!"
} | ConvertTo-Json

Write-Host "Ed sending message from $($edCreds.homeserver)..."
try {
    $response = Invoke-RestMethod -Uri $url -Method Put -Headers @{
        Authorization = "Bearer $($edCreds.access_token)"
        "Content-Type" = "application/json"
    } -Body $body
    
    Write-Host "✅ Ed's message sent successfully!"
    Write-Host "Event ID: $($response.event_id)"
    
    # Now check if ferretcannon can see it
    Write-Host "`nChecking if ferretcannon can see Ed's message..."
    Start-Sleep -Seconds 2
    
    $ferretcannonCreds = $credentials."ferretcannon.roflcopter.wtf"
    $messagesUrl = "$($ferretcannonCreds.homeserver)/_matrix/client/v3/rooms/$roomId/messages?dir=b&limit=5"
    
    $messages = Invoke-RestMethod -Uri $messagesUrl -Method Get -Headers @{
        Authorization = "Bearer $($ferretcannonCreds.access_token)"
    }
    
    Write-Host "`nRecent messages ferretcannon can see:"
    $messages.chunk | ForEach-Object {
        if ($_.type -eq "m.room.message") {
            Write-Host "  - From: $($_.sender)"
            Write-Host "    Body: $($_.content.body)"
        }
    }
    
} catch {
    Write-Host "❌ Error: $_"
    if ($_.ErrorDetails.Message) {
        Write-Host "Details: $($_.ErrorDetails.Message)"
    }
}
