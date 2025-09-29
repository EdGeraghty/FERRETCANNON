# Multi-stage build for FERRETCANNON Matrix Server

# Stage 1: Build stage
FROM eclipse-temurin:17-jdk-alpine as builder

# Set the working directory
WORKDIR /app

# Copy the Gradle wrapper and build files
# Force rebuild - v8
COPY gradlew build.gradle.kts settings.gradle.kts gradle.properties* timestamp.txt ./
COPY gradle/ gradle/

# Copy the source code
# Force rebuild - v9
COPY src/ src/

# Make the Gradle wrapper executable
RUN chmod +x gradlew

# Build the application
# Force rebuild - v10
RUN ./gradlew installDist --no-daemon -x test --no-configuration-cache --refresh-dependencies

# Stage 2: Runtime stage
FROM eclipse-temurin:17-jdk-alpine

# Set the working directory
WORKDIR /app

# Install security updates and remove cache (Alpine)
RUN apk update && apk upgrade && rm -rf /var/cache/apk/*

# Copy the built application from the builder stage
COPY --from=builder /app/build/install/FERRETCANNON/ /app/

# Copy configuration file (production version)
COPY config.prod.yml config.yml

# Create a script to conditionally delete the database based on isDebug config
RUN mkdir -p /data && \
    echo '#!/bin/sh' > /app/check_debug.sh && \
    echo 'if grep -q "isDebug: true" /app/config.yml; then rm -f /data/ferretcannon.db; fi' >> /app/check_debug.sh && \
    chmod +x /app/check_debug.sh

# Install sqlite for migration
RUN apk add --no-cache sqlite

# Copy migration script
COPY migrate_db.kts migrate_db.kts

# Generate a funny build version
RUN echo "Ferret Cannon Build $(date +%s | sha256sum | head -c 8)-$(shuf -n 1 -e 'Banana' 'Rocket' 'Ninja' 'Disco' 'Zombie' 'Unicorn' 'Pirate' 'Laser')" > /app/version.txt

# Create a wrapper script that includes migration
RUN echo '#!/bin/sh' > /app/start.sh && \
    echo './check_debug.sh' >> /app/start.sh && \
    echo 'echo "🚀 $(cat /app/version.txt)"' >> /app/start.sh && \
    echo 'echo "Starting database migration..."' >> /app/start.sh && \
    echo 'if [ -f "/data/ferretcannon.db" ]; then' >> /app/start.sh && \
    echo '  sqlite3 /data/ferretcannon.db "ALTER TABLE users ADD COLUMN is_admin BOOLEAN DEFAULT 0;" 2>/dev/null || echo "is_admin column may already exist"' >> /app/start.sh && \
    echo '  sqlite3 /data/ferretcannon.db "ALTER TABLE server_keys ADD COLUMN private_key TEXT;" 2>/dev/null || echo "private_key column may already exist"' >> /app/start.sh && \
    echo '  echo "Migration completed."' >> /app/start.sh && \
    echo 'else' >> /app/start.sh && \
    echo '  echo "Database not found, skipping migration."' >> /app/start.sh && \
    echo 'fi' >> /app/start.sh && \
    echo './bin/FERRETCANNON' >> /app/start.sh && \
    chmod +x /app/start.sh

# Expose the port the app runs on
EXPOSE 8080

# Set environment variables for production
ENV JAVA_OPTS="-Xmx512m -Xms256m"

# Force clean build - version 7 - 1727600000
# Run the application directly
CMD ["./start.sh"]
