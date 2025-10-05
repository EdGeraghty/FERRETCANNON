# Check the current room state for ferretcannon user
$SERVER = "https://ferretcannon.roflcopter.wtf"
$ROOM_ID = "!ljI2RJQdNNdO7hOrzMPrACPxwQZiKxMJhCTJtMXx-hw"
$ACCESS_TOKEN = Read-Host "Enter your access token" -AsSecureString
$ACCESS_TOKEN_PLAIN = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($ACCESS_TOKEN))

Write-Host "`nChecking room state..." -ForegroundColor Cyan

# Try to get room state
try {
    $stateUrl = "$SERVER/_matrix/client/v3/rooms/$ROOM_ID/state"
    $state = Invoke-RestMethod -Uri $stateUrl -Method Get -Headers @{
        "Authorization" = "Bearer $ACCESS_TOKEN_PLAIN"
    }
    
    Write-Host "`nRoom state retrieved successfully!" -ForegroundColor Green
    Write-Host "Total state events: $($state.Count)" -ForegroundColor Yellow
    
    # Find membership events for ferretcannon
    $membershipEvents = $state | Where-Object { 
        $_.'type' -eq 'm.room.member' -and 
        $_.'state_key' -eq '@ferretcannon:ferretcannon.roflcopter.wtf' 
    }
    
    Write-Host "`nMembership events for @ferretcannon:ferretcannon.roflcopter.wtf:" -ForegroundColor Yellow
    foreach ($event in $membershipEvents) {
        Write-Host "  Event ID: $($event.event_id)"
        Write-Host "  Membership: $($event.content.membership)"
        Write-Host "  Sender: $($event.sender)"
        Write-Host "  ---"
    }
    
} catch {
    Write-Host "`nFailed to get room state:" -ForegroundColor Red
    Write-Host $_.Exception.Message
    
    # Try to check sync instead
    Write-Host "`nTrying to check via /sync..." -ForegroundColor Cyan
    try {
        $syncUrl = "$SERVER/_matrix/client/v3/sync?timeout=0"
        $sync = Invoke-RestMethod -Uri $syncUrl -Method Get -Headers @{
            "Authorization" = "Bearer $ACCESS_TOKEN_PLAIN"
        }
        
        # Check if room is in invited rooms
        if ($sync.rooms.invite.$ROOM_ID) {
            Write-Host "Room is in INVITE state" -ForegroundColor Yellow
            Write-Host ($sync.rooms.invite.$ROOM_ID | ConvertTo-Json -Depth 5)
        }
        
        # Check if room is in joined rooms
        if ($sync.rooms.join.$ROOM_ID) {
            Write-Host "Room is in JOIN state" -ForegroundColor Green
            Write-Host "Timeline events: $($sync.rooms.join.$ROOM_ID.timeline.events.Count)"
        }
        
        # Check if room is in left rooms
        if ($sync.rooms.leave.$ROOM_ID) {
            Write-Host "Room is in LEAVE state" -ForegroundColor Red
        }
        
    } catch {
        Write-Host "Sync also failed:" -ForegroundColor Red
        Write-Host $_.Exception.Message
    }
}
