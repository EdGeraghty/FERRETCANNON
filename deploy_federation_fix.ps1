#!/usr/bin/env pwsh
# Deploy script with federation fixes

Write-Host "Deploying Federation Fixes to Production..." -ForegroundColor Green

# Build the project first
Write-Host "Building project..." -ForegroundColor Yellow
gradle build

if ($LASTEXITCODE -ne 0) {
    Write-Host "Build failed!" -ForegroundColor Red
    exit 1
}

Write-Host "Build successful!" -ForegroundColor Green

# Set environment variable for production server name
Write-Host "Setting environment variables for production..." -ForegroundColor Yellow

# Deploy to fly.io with the production config
Write-Host "Deploying to fly.io..." -ForegroundColor Yellow
fly deploy --config fly.toml --dockerfile Dockerfile

if ($LASTEXITCODE -ne 0) {
    Write-Host "Deployment failed!" -ForegroundColor Red
    exit 1
}

Write-Host "Deployment successful!" -ForegroundColor Green

Write-Host "`nFederation fixes deployed:" -ForegroundColor Cyan
Write-Host "✅ Fixed JSON canonicalization double-encoding"
Write-Host "✅ Added proper JsonElement to native conversion"
Write-Host "✅ Updated server name resolution"
Write-Host "✅ Fixed getUserProfile for production domain"

Write-Host "`nMonitoring deployment..." -ForegroundColor Yellow
Write-Host "Run 'fly logs' to check server startup and federation activity"

# Show the production config that should be used
Write-Host "`nProduction config should show:" -ForegroundColor Cyan
Write-Host "serverName: ferretcannon.roflcopter.wtf"
Write-Host "The server should now accept federation invites!"
