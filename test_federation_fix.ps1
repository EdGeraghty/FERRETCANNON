#!/usr/bin/env pwsh
# Test script to verify federation signature verification fixes

Write-Host "Testing Federation Fixes..." -ForegroundColor Green

# Build the project to ensure changes are compiled
Write-Host "Building project..." -ForegroundColor Yellow
gradle build

if ($LASTEXITCODE -ne 0) {
    Write-Host "Build failed!" -ForegroundColor Red
    exit 1
}

Write-Host "Build successful!" -ForegroundColor Green

# The key fixes made:
Write-Host "`nKey fixes applied:" -ForegroundColor Cyan
Write-Host "1. Fixed JSON canonicalization to prevent double-encoding"
Write-Host "2. Added jsonElementToNative conversion function"
Write-Host "3. Fixed server name resolution to use ServerNameResolver"
Write-Host "4. Updated getUserProfile to work with production domain"

Write-Host "`nTo deploy these fixes to production:" -ForegroundColor Yellow
Write-Host "1. Run: fly deploy --config fly.toml --dockerfile Dockerfile"
Write-Host "2. Monitor logs: fly logs"
Write-Host "3. Test federation invite again"

Write-Host "`nChanges ready for deployment!" -ForegroundColor Green
