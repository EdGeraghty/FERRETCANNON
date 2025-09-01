# Test script for capabilities endpoint
# This script tests the GET /_matrix/client/v3/capabilities endpoint

Write-Host "=== FERRETCANNON Matrix Server - Capabilities Endpoint Test ===" -ForegroundColor Green
Write-Host ""

# Server URL
$SERVER_URL = "http://localhost:8080"

Write-Host "1. Registering a test user..." -ForegroundColor Yellow
$REGISTER_BODY = @{
    auth = @{
        type = "m.login.password"
    }
    username = "testuser_capabilities"
    password = "TestPassword123!"
    device_id = "capabilities_test_device"
} | ConvertTo-Json

try {
    $REGISTER_RESPONSE = Invoke-RestMethod -Uri "$SERVER_URL/_matrix/client/v3/register" -Method POST -Body $REGISTER_BODY -ContentType "application/json"
    Write-Host "Registration response:" -ForegroundColor Cyan
    $REGISTER_RESPONSE | ConvertTo-Json
    Write-Host ""
} catch {
    Write-Host "Registration failed:" -ForegroundColor Red
    $_.Exception.Message
    exit 1
}

# Extract access token and user ID
$ACCESS_TOKEN = $REGISTER_RESPONSE.access_token
$USER_ID = $REGISTER_RESPONSE.user_id

if (-not $ACCESS_TOKEN) {
    Write-Host "Failed to register user or get access token" -ForegroundColor Red
    exit 1
}

Write-Host "2. Testing capabilities endpoint..." -ForegroundColor Yellow
try {
    $CAPABILITIES_RESPONSE = Invoke-RestMethod -Uri "$SERVER_URL/_matrix/client/v3/capabilities" -Method GET -Headers @{Authorization = "Bearer $ACCESS_TOKEN"}
    Write-Host "Capabilities response:" -ForegroundColor Cyan
    $CAPABILITIES_RESPONSE | ConvertTo-Json -Depth 10
    Write-Host ""
} catch {
    Write-Host "Capabilities endpoint failed:" -ForegroundColor Red
    $_.Exception.Message
    Write-Host ""
}

Write-Host "3. Testing capabilities endpoint without authentication (should fail)..." -ForegroundColor Yellow
try {
    $NO_AUTH_RESPONSE = Invoke-RestMethod -Uri "$SERVER_URL/_matrix/client/v3/capabilities" -Method GET
    Write-Host "Unexpected success without authentication:" -ForegroundColor Red
    $NO_AUTH_RESPONSE | ConvertTo-Json
} catch {
    Write-Host "Expected failure without authentication:" -ForegroundColor Green
    $_.Exception.Message
    Write-Host ""
}

Write-Host "=== Capabilities Test completed! ===" -ForegroundColor Green
