#!/bin/sh

# Migration script to add missing columns to existing database
echo "Starting database migration..."

# Check if database exists
if [ ! -f "/data/ferretcannon.db" ]; then
    echo "Database file does not exist at /data/ferretcannon.db"
    exit 1
fi

# Check if sqlite3 is available
if ! command -v sqlite3 >/dev/null 2>&1; then
    echo "sqlite3 command not found"
    exit 1
fi

echo "Running migration on /data/ferretcannon.db"

# Add is_admin column if it doesn't exist
sqlite3 /data/ferretcannon.db << 'EOF'
-- Check if is_admin column exists
.schema users
EOF

# Try to add the column (this will fail silently if it already exists)
sqlite3 /data/ferretcannon.db "ALTER TABLE users ADD COLUMN is_admin BOOLEAN DEFAULT 0;" 2>/dev/null || echo "Column is_admin may already exist"

echo "Migration completed."
echo "Final schema:"
sqlite3 /data/ferretcannon.db ".schema users"
