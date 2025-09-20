# Test Account Data Endpoints
# This script tests the account data functionality that was causing 403 errors

$baseUrl = "http://localhost:8080"

Write-Host "Testing Account Data Endpoints..." -ForegroundColor Green
Write-Host "=========================================="

# First, register a test user to get a valid access token
Write-Host "Registering test user..." -ForegroundColor Yellow
$registerBody = @{
    auth = @{
        type = "m.login.password"
    }
    username = "testuser_account_data"
    password = "TestPassword123!"
    device_id = "account_data_test_device"
} | ConvertTo-Json

try {
    $registerResponse = Invoke-RestMethod -Uri "$baseUrl/_matrix/client/v3/register" -Method POST -Body $registerBody -ContentType "application/json"
    $accessToken = $registerResponse.access_token
    $userId = $registerResponse.user_id
    Write-Host "Registration successful! User: $userId" -ForegroundColor Green
} catch {
    Write-Host "Registration failed: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}

Write-Host "Using access token: $accessToken" -ForegroundColor Cyan
Write-Host ""

# Test 1: GET account data (should return 404 if not set)
Write-Host "Test 1: GET /user/{userId}/account_data/org.matrix.msc3890.local_notification_settings.default_device" -ForegroundColor Yellow
try {
    $response = Invoke-RestMethod -Uri "$baseUrl/_matrix/client/v3/user/$userId/account_data/org.matrix.msc3890.local_notification_settings.default_device" -Method GET -Headers @{
        "Authorization" = "Bearer $accessToken"
    }
    Write-Host "SUCCESS: $($response | ConvertTo-Json)" -ForegroundColor Green
} catch {
    Write-Host "Response: $($_.Exception.Response.StatusCode) - $($_.Exception.Message)" -ForegroundColor Red
}

# Test 2: PUT account data (should succeed)
Write-Host "`nTest 2: PUT /user/{userId}/account_data/org.matrix.msc3890.local_notification_settings.default_device" -ForegroundColor Yellow
$body = @{
    "is_silenced" = $true
} | ConvertTo-Json

try {
    $response = Invoke-RestMethod -Uri "$baseUrl/_matrix/client/v3/user/$userId/account_data/org.matrix.msc3890.local_notification_settings.default_device" -Method PUT -Headers @{
        "Authorization" = "Bearer $accessToken"
        "Content-Type" = "application/json"
    } -Body $body
    Write-Host "SUCCESS: Account data set successfully" -ForegroundColor Green
} catch {
    Write-Host "Response: $($_.Exception.Response.StatusCode) - $($_.Exception.Message)" -ForegroundColor Red
}

# Test 3: GET account data again (should return the data we just set)
Write-Host "`nTest 3: GET /user/{userId}/account_data/org.matrix.msc3890.local_notification_settings.default_device (after setting)" -ForegroundColor Yellow
try {
    $response = Invoke-RestMethod -Uri "$baseUrl/_matrix/client/v3/user/$userId/account_data/org.matrix.msc3890.local_notification_settings.default_device" -Method GET -Headers @{
        "Authorization" = "Bearer $accessToken"
    }
    Write-Host "SUCCESS: $($response | ConvertTo-Json)" -ForegroundColor Green
} catch {
    Write-Host "Response: $($_.Exception.Response.StatusCode) - $($_.Exception.Message)" -ForegroundColor Red
}

# Test 4: Test with wrong user ID (should return 403)
Write-Host "`nTest 4: GET /user/@wronguser:localhost/account_data/... (should fail with 403)" -ForegroundColor Yellow
try {
    $response = Invoke-RestMethod -Uri "$baseUrl/_matrix/client/v3/user/@wronguser:localhost/account_data/org.matrix.msc3890.local_notification_settings.default_device" -Method GET -Headers @{
        "Authorization" = "Bearer $accessToken"
    }
    Write-Host "UNEXPECTED: Should have failed with 403" -ForegroundColor Red
} catch {
    if ($_.Exception.Response.StatusCode -eq 403) {
        Write-Host "SUCCESS: Correctly returned 403 Forbidden" -ForegroundColor Green
    } else {
        Write-Host "Response: $($_.Exception.Response.StatusCode) - $($_.Exception.Message)" -ForegroundColor Red
    }
}

Write-Host "`nAccount Data Tests Complete!" -ForegroundColor Green
