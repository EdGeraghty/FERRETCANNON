# Test script for room creation on production server
# This script tests creating a room on the deployed ferretcannon.roflcopter.wtf server

# Ignore SSL certificate validation errors
[System.Net.ServicePointManager]::ServerCertificateValidationCallback = {$true}

Write-Host "=== FERRETCANNON Matrix Server - Production Room Creation Test ===" -ForegroundColor Green
Write-Host ""

# Production server URL
$SERVER_URL = "https://ferretcannon.roflcopter.wtf"

Write-Host "Testing room creation on production server: $SERVER_URL" -ForegroundColor Yellow
Write-Host ""

# First, try to register a test user
Write-Host "1. Registering test user..." -ForegroundColor Yellow
$REGISTER_BODY = @{
    auth = @{
        type = "m.login.dummy"
    }
    username = "prodtestuser"
    password = "TestPass123!"
} | ConvertTo-Json

try {
    $REGISTER_RESPONSE = Invoke-RestMethod -Uri "$SERVER_URL/_matrix/client/v3/register" -Method POST -Body $REGISTER_BODY -ContentType "application/json" -UseBasicParsing
    Write-Host "User registration response:" -ForegroundColor Cyan
    $REGISTER_RESPONSE | ConvertTo-Json
    Write-Host ""
    $ACCESS_TOKEN = $REGISTER_RESPONSE.access_token
    $USER_ID = $REGISTER_RESPONSE.user_id
} catch {
    Write-Host "User registration failed:" -ForegroundColor Red
    $_.Exception.Message
    Write-Host ""

    # If registration fails, try to login with existing credentials
    Write-Host "2. Attempting login with existing test credentials..." -ForegroundColor Yellow
    $LOGIN_BODY = @{
        type = "m.login.password"
        user = "testuser"
        password = "TestPass123!"
    } | ConvertTo-Json

    try {
        $LOGIN_RESPONSE = Invoke-RestMethod -Uri "$SERVER_URL/_matrix/client/v3/login" -Method POST -Body $LOGIN_BODY -ContentType "application/json" -UseBasicParsing
        Write-Host "Login response:" -ForegroundColor Cyan
        $LOGIN_RESPONSE | ConvertTo-Json
        Write-Host ""
        $ACCESS_TOKEN = $LOGIN_RESPONSE.access_token
        $USER_ID = $LOGIN_RESPONSE.user_id
    } catch {
        Write-Host "Login also failed:" -ForegroundColor Red
        $_.Exception.Message
        exit 1
    }
}

if (-not $ACCESS_TOKEN) {
    Write-Host "Failed to get access token" -ForegroundColor Red
    exit 1
}

Write-Host "3. Creating a test room..." -ForegroundColor Yellow
$CREATE_ROOM_BODY = @{
    name = "Production Test Room"
    topic = "Testing room creation after crypto fixes"
    preset = "public_chat"
} | ConvertTo-Json

try {
    $CREATE_ROOM_RESPONSE = Invoke-RestMethod -Uri "$SERVER_URL/_matrix/client/v3/createRoom" -Method POST -Body $CREATE_ROOM_BODY -ContentType "application/json" -Headers @{Authorization = "Bearer $ACCESS_TOKEN"} -UseBasicParsing
    Write-Host "Room creation response:" -ForegroundColor Green
    $CREATE_ROOM_RESPONSE | ConvertTo-Json
    Write-Host ""
    $ROOM_ID = $CREATE_ROOM_RESPONSE.room_id

    Write-Host "=== SUCCESS: Room created successfully! ===" -ForegroundColor Green
    Write-Host "Room ID: $ROOM_ID" -ForegroundColor Cyan
    Write-Host "User ID: $USER_ID" -ForegroundColor Cyan
    Write-Host "Server: $SERVER_URL" -ForegroundColor Cyan

} catch {
    Write-Host "Room creation failed:" -ForegroundColor Red
    $_.Exception.Message
    Write-Host ""
    Write-Host "=== FAILURE: Room creation test failed ===" -ForegroundColor Red
    exit 1
}
