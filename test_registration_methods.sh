#!/usr/bin/env bash

# Test script for registration methods query endpoint
# This script tests the GET /_matrix/client/v3/register endpoint

echo "=== FERRETCANNON Matrix Server - Registration Methods Test ==="
echo ""

# Server URL
SERVER_URL="http://localhost:8080"

echo "Testing GET /register endpoint for supported registration methods..."
REGISTER_METHODS_RESPONSE=$(curl -s -X GET "$SERVER_URL/_matrix/client/v3/register")

echo "Registration methods response:"
echo "$REGISTER_METHODS_RESPONSE" | jq '.' 2>/dev/null || echo "$REGISTER_METHODS_RESPONSE"
echo ""

echo "Testing GET /login endpoint for supported login flows..."
LOGIN_FLOWS_RESPONSE=$(curl -s -X GET "$SERVER_URL/_matrix/client/v3/login")

echo "Login flows response:"
echo "$LOGIN_FLOWS_RESPONSE" | jq '.' 2>/dev/null || echo "$LOGIN_FLOWS_RESPONSE"
echo ""

echo "Test completed."
