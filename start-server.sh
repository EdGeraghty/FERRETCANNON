#!/bin/bash

# FERRETCANNON Matrix Server Startup Script for macOS
# This script starts the server in the background

echo "🚀 Starting FERRETCANNON Matrix Server..."

# Kill any existing server processes
pkill -f "gradle.*run" 2>/dev/null || true
pkill -f "java.*MainKt" 2>/dev/null || true

# Wait a moment for processes to terminate
sleep 2

# Start the server in background
./gradlew run &
SERVER_PID=$!

echo "✅ Server started with PID: $SERVER_PID"
echo "🌐 Server should be available at http://localhost:8080"
echo "🛑 To stop the server, run: kill $SERVER_PID"
echo "📋 Or use: pkill -f 'gradle.*run'"

# Wait a moment for server to start
sleep 3

# Check if server is responding
if curl -s http://localhost:8080/_matrix/client/v3/login > /dev/null 2>&1; then
    echo "✅ Server is responding!"
else
    echo "⚠️  Server may still be starting up..."
fi

echo "🎉 Server is running in background. You can now use this terminal for other commands."
