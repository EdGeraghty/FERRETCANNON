# Test script for room directory endpoint (room alias resolution)
# Tests GET /_matrix/client/v3/directory/room/{roomAlias}

$SERVER = "https://ferretcannon.roflcopter.wtf"
$ACCESS_TOKEN = "JkNxbPE3UqlPsKDtI-PGKhugFfPVyZHh1ciDQ1WZ7GE"
$ROOM_ALIAS = "#ferretcannon:geraghty.family"

# URL encode the room alias
$encodedAlias = [System.Uri]::EscapeDataString($ROOM_ALIAS)
$directoryUrl = "$SERVER/_matrix/client/v3/directory/room/$encodedAlias"

Write-Host "Testing Room Directory Endpoint" -ForegroundColor Cyan
Write-Host "================================" -ForegroundColor Cyan
Write-Host "Server: $SERVER" -ForegroundColor White
Write-Host "Room Alias: $ROOM_ALIAS" -ForegroundColor White
Write-Host "URL: $directoryUrl" -ForegroundColor White
Write-Host ""

try {
    $response = Invoke-RestMethod -Uri $directoryUrl -Method Get `
        -Headers @{
            "Authorization" = "Bearer $ACCESS_TOKEN"
        }
    
    Write-Host "✅ SUCCESS! Room alias resolved:" -ForegroundColor Green
    Write-Host ($response | ConvertTo-Json -Depth 10)
    
    Write-Host ""
    Write-Host "Room ID: $($response.room_id)" -ForegroundColor Yellow
    Write-Host "Servers: $($response.servers -join ', ')" -ForegroundColor Yellow
    
} catch {
    Write-Host "❌ ERROR:" -ForegroundColor Red
    Write-Host $_.Exception.Message
    if ($_.ErrorDetails) {
        Write-Host $_.ErrorDetails.Message
    }
    Write-Host ""
    Write-Host "Status Code: $($_.Exception.Response.StatusCode.value__)" -ForegroundColor Red
}
