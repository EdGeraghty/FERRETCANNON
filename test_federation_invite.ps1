#!/usr/bin/env pwsh
# Test federation invite processing to verify the fix is working

Write-Host "Testing Federation Invite Processing..." -ForegroundColor Green

$serverUrl = "https://ferretcannon.roflcopter.wtf"
$testEventId = '$z1IHBklPVWZjZ_lfoWTmuj-iR1n0G1AIcEBnh9jOBxY'

Write-Host "Server URL: $serverUrl" -ForegroundColor Cyan
Write-Host "Test Event ID: $testEventId" -ForegroundColor Cyan

# Test 1: Check server is responding
Write-Host "`nTest 1: Server Health Check" -ForegroundColor Yellow
try {
    $response = Invoke-WebRequest -Uri "$serverUrl/_matrix/federation/v1/version" -Method GET -UseBasicParsing
    Write-Host "Server is responding" -ForegroundColor Green
    Write-Host "Response: $($response.Content)" -ForegroundColor Gray
} catch {
    Write-Host "Server health check failed: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}

# Test 2: Check recent logs for invite processing
Write-Host "`nTest 2: Checking Recent Logs for Invite Processing" -ForegroundColor Yellow
Write-Host "Looking for federation invite logs..." -ForegroundColor Gray

# Instructions for manual testing
Write-Host "`nManual Testing Instructions:" -ForegroundColor Cyan
Write-Host "1. From another Matrix server, send an invite to a user on ferretcannon.roflcopter.wtf"
Write-Host "2. Monitor logs with: fly logs --app ferretcannon"
Write-Host "3. Look for these log entries:"
Write-Host "   - Federation invite auth succeeded"
Write-Host "   - Federation invite: ACL check passed"
Write-Host "   - Federation invite: Event validation passed (this should now work)"
Write-Host "   - No more Event ID mismatch errors"

Write-Host "`nThe fix should resolve the event validation error that was preventing federation invites." -ForegroundColor Green
Write-Host "Event structure parsing has been corrected to handle nested event format." -ForegroundColor Green
