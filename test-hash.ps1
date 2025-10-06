#!/usr/bin/env pwsh
# Test script to verify our hash computation

$canonicalJson = '{"auth_events":["$amxREh8Yxdn7Ajg1isWwiE8vThUUrMTMeAn9vK4iANA","$sQzYfaDpsQJg93gkc-0KBVzcKgBTjuzPUQ31aGFxEj4"],"content":{"membership":"join"},"depth":1399,"origin_server_ts":1759753025984,"prev_events":["$jtmwoL0SodCgNmjPwlPdhUVsAIA-elkCs_FI6VO78dQ"],"room_id":"!ljI2RJQdNNdO7hOrzMPrACPxwQZiKxMJhCTJtMXx-hw","sender":"@ferretcannon:ferretcannon.roflcopter.wtf","state_key":"@ferretcannon:ferretcannon.roflcopter.wtf","type":"m.room.member"}'

Write-Host "Canonical JSON:" -ForegroundColor Cyan
Write-Host $canonicalJson

$bytes = [System.Text.Encoding]::UTF8.GetBytes($canonicalJson)
Write-Host "`nUTF-8 Bytes length: $($bytes.Length)" -ForegroundColor Yellow

$sha256 = [System.Security.Cryptography.SHA256]::Create()
$hash = $sha256.ComputeHash($bytes)

# Convert to Base64URL (no padding)
$base64 = [Convert]::ToBase64String($hash)
$base64url = $base64.Replace('+', '-').Replace('/', '_').TrimEnd('=')

Write-Host "`nSHA-256 Hash (Base64): $base64" -ForegroundColor Green
Write-Host "SHA-256 Hash (Base64URL, no padding): $base64url" -ForegroundColor Green

Write-Host "`nExpected hash from logs: x8vNFczuKAWlLMO-F7XWzAZRCS0zlplC6l7HcxihZfQ" -ForegroundColor Magenta
Write-Host "Match: $(if ($base64url -eq 'x8vNFczuKAWlLMO-F7XWzAZRCS0zlplC6l7HcxihZfQ') { 'YES' } else { 'NO' })" -ForegroundColor $(if ($base64url -eq 'x8vNFczuKAWlLMO-F7XWzAZRCS0zlplC6l7HcxihZfQ') { 'Green' } else { 'Red' })
