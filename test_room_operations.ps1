# Test script for room creation, joining, and leaving
# This script tests creating a room, joining it, and leaving it

Write-Host "=== FERRETCANNON Matrix Server - Room Creation, Joining, and Leaving Test ===" -ForegroundColor Green
Write-Host ""

# Server URL
$SERVER_URL = "http://localhost:8080"

Write-Host "1. Registering first test user..." -ForegroundColor Yellow
$REGISTER_BODY1 = @{
    auth = @{
        type = "m.login.dummy"
    }
    username = "roomtestuser1"
} | ConvertTo-Json

try {
    $REGISTER_RESPONSE1 = Invoke-RestMethod -Uri "$SERVER_URL/_matrix/client/v3/register" -Method POST -Body $REGISTER_BODY1 -ContentType "application/json"
    Write-Host "User 1 registration response:" -ForegroundColor Cyan
    $REGISTER_RESPONSE1 | ConvertTo-Json
    Write-Host ""
} catch {
    Write-Host "User 1 registration failed:" -ForegroundColor Red
    $_.Exception.Message
    exit 1
}

# Extract access token and user ID for user 1
$ACCESS_TOKEN1 = $REGISTER_RESPONSE1.access_token
$USER_ID1 = $REGISTER_RESPONSE1.user_id

if (-not $ACCESS_TOKEN1) {
    Write-Host "Failed to register user 1 or get access token" -ForegroundColor Red
    exit 1
}

Write-Host "2. Registering second test user..." -ForegroundColor Yellow
$REGISTER_BODY2 = @{
    auth = @{
        type = "m.login.dummy"
    }
    username = "roomtestuser2"
} | ConvertTo-Json

try {
    $REGISTER_RESPONSE2 = Invoke-RestMethod -Uri "$SERVER_URL/_matrix/client/v3/register" -Method POST -Body $REGISTER_BODY2 -ContentType "application/json"
    Write-Host "User 2 registration response:" -ForegroundColor Cyan
    $REGISTER_RESPONSE2 | ConvertTo-Json
    Write-Host ""
} catch {
    Write-Host "User 2 registration failed:" -ForegroundColor Red
    $_.Exception.Message
    exit 1
}

# Extract access token and user ID for user 2
$ACCESS_TOKEN2 = $REGISTER_RESPONSE2.access_token
$USER_ID2 = $REGISTER_RESPONSE2.user_id

if (-not $ACCESS_TOKEN2) {
    Write-Host "Failed to register user 2 or get access token" -ForegroundColor Red
    exit 1
}

Write-Host "3. User 1 creating a room..." -ForegroundColor Yellow
$CREATE_ROOM_BODY = @{
    name = "Test Room"
    topic = "A room for testing room creation and joining"
    preset = "public_chat"
} | ConvertTo-Json

try {
    $CREATE_ROOM_RESPONSE = Invoke-RestMethod -Uri "$SERVER_URL/_matrix/client/v3/createRoom" -Method POST -Body $CREATE_ROOM_BODY -ContentType "application/json" -Headers @{Authorization = "Bearer $ACCESS_TOKEN1"}
    Write-Host "Room creation response:" -ForegroundColor Cyan
    $CREATE_ROOM_RESPONSE | ConvertTo-Json
    Write-Host ""
} catch {
    Write-Host "Room creation failed:" -ForegroundColor Red
    $_.Exception.Message
    exit 1
}

# Extract room ID
$ROOM_ID = $CREATE_ROOM_RESPONSE.room_id

if (-not $ROOM_ID) {
    Write-Host "Failed to create room or get room ID" -ForegroundColor Red
    exit 1
}

Write-Host "4. User 2 joining the room..." -ForegroundColor Yellow
$JOIN_ROOM_BODY = @{} | ConvertTo-Json

try {
    $JOIN_ROOM_RESPONSE = Invoke-RestMethod -Uri "$SERVER_URL/_matrix/client/v3/rooms/$ROOM_ID/join" -Method POST -Body $JOIN_ROOM_BODY -ContentType "application/json" -Headers @{Authorization = "Bearer $ACCESS_TOKEN2"}
    Write-Host "Room join response:" -ForegroundColor Cyan
    $JOIN_ROOM_RESPONSE | ConvertTo-Json
    Write-Host ""
} catch {
    Write-Host "Room join failed:" -ForegroundColor Red
    $_.Exception.Message
    exit 1
}

Write-Host "5. Verifying room membership..." -ForegroundColor Yellow
try {
    $ROOM_STATE_RESPONSE = Invoke-RestMethod -Uri "$SERVER_URL/_matrix/client/v3/rooms/$ROOM_ID/state" -Method GET -Headers @{Authorization = "Bearer $ACCESS_TOKEN1"}
    Write-Host "Room state response:" -ForegroundColor Cyan
    $ROOM_STATE_RESPONSE | ConvertTo-Json -Depth 5
    Write-Host ""
} catch {
    Write-Host "Failed to get room state:" -ForegroundColor Red
    $_.Exception.Message
    Write-Host ""
}

Write-Host "6. User 2 leaving the room..." -ForegroundColor Yellow
$LEAVE_ROOM_BODY = @{} | ConvertTo-Json

try {
    $LEAVE_ROOM_RESPONSE = Invoke-RestMethod -Uri "$SERVER_URL/_matrix/client/v3/rooms/$ROOM_ID/leave" -Method POST -Body $LEAVE_ROOM_BODY -ContentType "application/json" -Headers @{Authorization = "Bearer $ACCESS_TOKEN2"}
    Write-Host "Room leave response:" -ForegroundColor Cyan
    $LEAVE_ROOM_RESPONSE | ConvertTo-Json
    Write-Host ""
} catch {
    Write-Host "Room leave failed:" -ForegroundColor Red
    $_.Exception.Message
    exit 1
}

Write-Host "7. Verifying room membership after leave..." -ForegroundColor Yellow
try {
    $ROOM_STATE_RESPONSE2 = Invoke-RestMethod -Uri "$SERVER_URL/_matrix/client/v3/rooms/$ROOM_ID/state" -Method GET -Headers @{Authorization = "Bearer $ACCESS_TOKEN1"}
    Write-Host "Room state response after leave:" -ForegroundColor Cyan
    $ROOM_STATE_RESPONSE2 | ConvertTo-Json -Depth 5
    Write-Host ""
} catch {
    Write-Host "Failed to get room state after leave:" -ForegroundColor Red
    $_.Exception.Message
    Write-Host ""
}

Write-Host "=== Room Creation, Joining, and Leaving Test completed successfully! ===" -ForegroundColor Green
Write-Host "Room ID: $ROOM_ID" -ForegroundColor Cyan
Write-Host "User 1: $USER_ID1" -ForegroundColor Cyan
Write-Host "User 2: $USER_ID2" -ForegroundColor Cyan
