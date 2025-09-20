# Test script for capabilities endpoint
# This script tests the GET /_matrix/client/v3/capabilities endpoint

Write-Host "=== FERRETCANNON Matrix Server - Capabilities Endpoint Test ===" -ForegroundColor Green
Write-Host ""

# Server URL
$SERVER_URL = "http://localhost:8080"

Write-Host "1. Testing capabilities endpoint with existing test user..." -ForegroundColor Yellow
$ACCESS_TOKEN = "FucJYFOn1oPU-Z4lDcAbCnw50ONFGEjN7KH2oz78oPs"
$USER_ID = "@testuser:localhost"

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
