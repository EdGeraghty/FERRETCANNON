# Test script to manually join the room via Matrix API
# To get your access token from Element Web:
# 1. Open Element Web (app.element.io or your Element instance)
# 2. Press F12 to open Developer Tools
# 3. Go to Console tab
# 4. Type: localStorage.getItem("mx_access_token")
# 5. Copy the token (without quotes) and paste it below

$SERVER = "https://ferretcannon.roflcopter.wtf"
$ACCESS_TOKEN = "JkNxbPE3UqlPsKDtI-PGKhugFfPVyZHh1ciDQ1WZ7GE"
$ROOM_ID = "!ljI2RJQdNNdO7hOrzMPrACPxwQZiKxMJhCTJtMXx-hw"

# Try joining without server_name first
$joinUrl = "$SERVER/_matrix/client/v3/rooms/$ROOM_ID/join"

Write-Host "Attempting to join room WITHOUT server_name parameter..." -ForegroundColor Cyan
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
    Write-Host "Failed: $($_.Exception.Message)" -ForegroundColor Red
    if ($_.ErrorDetails) {
        Write-Host $_.ErrorDetails.Message
    }
}

Write-Host ""
Write-Host "Now trying WITH server_name parameter..." -ForegroundColor Cyan

# Join room with server_name parameter
$joinUrl = "$SERVER/_matrix/client/v3/rooms/$ROOM_ID/join?server_name=geraghty.family"

Write-Host "Attempting to join room via: $joinUrl" -ForegroundColor Green
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
