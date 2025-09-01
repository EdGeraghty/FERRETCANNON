@echo off
echo ========================================
echo  FERRETCANNON Matrix Server - Fly.io Deployment
echo ========================================
echo.

echo Step 1: Installing flyctl CLI...
echo If you haven't installed flyctl yet, download it from:
echo https://fly.io/docs/flyctl/install/
echo.

echo Step 2: Login to fly.io...
fly auth login
echo.

echo Step 3: Initialize the app...
fly launch --copy-config
echo.

echo Step 4: Deploy the app...
fly deploy
echo.

echo Step 5: Check app status...
fly status
echo.

echo Step 6: Get app URL...
fly open
echo.

echo ========================================
echo  IMPORTANT CONFIGURATION STEPS:
echo ========================================
echo.
echo 1. Update config.prod.yml with your actual server name
echo 2. Change the JWT secret to a secure random value
echo 3. Configure CORS origins for your domain
echo 4. Set up federation server name if needed
echo.
echo Your Matrix server will be available at:
echo https://your-app-name.fly.dev
echo.
echo To check logs: fly logs
echo To scale: fly scale count 2
echo To stop: fly apps destroy your-app-name
echo.

pause
