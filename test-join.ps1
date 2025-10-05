# Test script to manually join the room via Matrix API
# To get your access token from Element Web:
# 1. Open Element Web (app.element.io or your Element instance)
# 2. Press F12 to open Developer Tools
# 3. Go to Console tab
# 4. Type: localStorage.getItem("mx_access_token")
# 5. Copy the token (without quotes) and paste it below

$SERVER = "https://ferretcannon.roflcopter.wtf"
$ACCESS_TOKEN = Read-Host "Enter your Element access token"
$ROOM_ID = "!ljI2RJQdNNdO7hOrzMPrACPxwQZiKxMJhCTJtMXx-hw"

# Join room with server_name parameter
$joinUrl = "$SERVER/_matrix/client/v3/rooms/$ROOM_ID/join?server_name=geraghty.family"

Write-Host "Attempting to join room via: $joinUrl" -ForegroundColor Green
Write-Host "Using access token: $ACCESS_TOKEN" -ForegroundColor Yellow
Write-Host ""

try {
    $response = Invoke-RestMethod -Uri $joinUrl -Method Post `
        -Headers @{
            "Authorization" = "Bearer $ACCESS_TOKEN"
            "Content-Type" = "application/json"
        } `
        -Body "{}"
    
    Write-Host "SUCCESS! Joined room:" -ForegroundColor Green
    Write-Host ($response | ConvertTo-Json -Depth 10)
} catch {
    Write-Host "ERROR:" -ForegroundColor Red
    Write-Host $_.Exception.Message
    if ($_.ErrorDetails) {
        Write-Host $_.ErrorDetails.Message
    }
}
