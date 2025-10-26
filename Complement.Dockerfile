# Complement.Dockerfile for FERRETCANNON Matrix Server
# This Dockerfile is specifically designed for running Complement integration tests
# against the FERRETCANNON Matrix homeserver implementation.
#
# Complement is the official Matrix compliance test suite that validates
# homeserver implementations against the Matrix Specification v1.16.
#
# Big shoutout to the FERRETCANNON massive for spec compliance! 🎆

# Stage 1: Build stage
FROM eclipse-temurin:17-jdk-alpine AS builder

WORKDIR /app

# Install required build tools
RUN apk add --no-cache wget unzip

# Download and install Gradle
RUN wget https://services.gradle.org/distributions/gradle-9.0.0-bin.zip -P /tmp && \
    unzip -d /opt/gradle /tmp/gradle-9.0.0-bin.zip && \
    rm /tmp/gradle-9.0.0-bin.zip

ENV GRADLE_HOME=/opt/gradle/gradle-9.0.0
ENV PATH=${GRADLE_HOME}/bin:${PATH}

# Copy build files
COPY build.gradle.kts settings.gradle.kts gradle.properties* ./

# Copy the source code
COPY src/ src/

# Build the application without running tests
RUN gradle installDist --no-daemon -x test --no-configuration-cache

# Stage 2: Runtime stage for Complement testing
FROM eclipse-temurin:17-jdk-alpine

WORKDIR /app

# Install required packages for Complement
RUN apk update && \
    apk upgrade && \
    apk add --no-cache sqlite wget curl && \
    rm -rf /var/cache/apk/*

# Copy the built application from the builder stage
COPY --from=builder /app/build/install/FERRETCANNON/ /app/

# Create directories for data and configuration
RUN mkdir -p /data /conf

# Create a Complement-specific configuration template
# This will be populated by the entrypoint script based on environment variables
RUN echo 'server:' > /conf/config.template.yml && \
    echo '  host: 0.0.0.0' >> /conf/config.template.yml && \
    echo '  port: 8008' >> /conf/config.template.yml && \
    echo '  maxRequestSize: 10485760' >> /conf/config.template.yml && \
    echo '  corsAllowedOrigins:' >> /conf/config.template.yml && \
    echo '    - "*"' >> /conf/config.template.yml && \
    echo '' >> /conf/config.template.yml && \
    echo 'database:' >> /conf/config.template.yml && \
    echo '  url: jdbc:sqlite:/data/ferretcannon.db' >> /conf/config.template.yml && \
    echo '  driver: org.sqlite.JDBC' >> /conf/config.template.yml && \
    echo '  maxConnections: 10' >> /conf/config.template.yml && \
    echo '  connectionTimeout: 30000' >> /conf/config.template.yml && \
    echo '' >> /conf/config.template.yml && \
    echo 'federation:' >> /conf/config.template.yml && \
    echo '  serverName: ${SERVER_NAME}' >> /conf/config.template.yml && \
    echo '  federationPort: 8008' >> /conf/config.template.yml && \
    echo '  enableFederation: true' >> /conf/config.template.yml && \
    echo '  allowedServers:' >> /conf/config.template.yml && \
    echo '    - "*"' >> /conf/config.template.yml && \
    echo '  keyValidityPeriod: 86400000' >> /conf/config.template.yml && \
    echo '' >> /conf/config.template.yml && \
    echo 'security:' >> /conf/config.template.yml && \
    echo '  jwtSecret: complement-test-secret-key-do-not-use-in-production' >> /conf/config.template.yml && \
    echo '  tokenExpirationHours: 24' >> /conf/config.template.yml && \
    echo '  bcryptRounds: 4' >> /conf/config.template.yml && \
    echo '  rateLimitRequests: 300' >> /conf/config.template.yml && \
    echo '  rateLimitPeriodMinutes: 1' >> /conf/config.template.yml && \
    echo '  enableRateLimiting: false' >> /conf/config.template.yml && \
    echo '  disableRegistration: false' >> /conf/config.template.yml && \
    echo '' >> /conf/config.template.yml && \
    echo 'media:' >> /conf/config.template.yml && \
    echo '  basePath: /data/media' >> /conf/config.template.yml && \
    echo '  maxUploadSize: 10485760' >> /conf/config.template.yml && \
    echo '  maxImageSize: 10485760' >> /conf/config.template.yml && \
    echo '  maxAvatarSize: 1048576' >> /conf/config.template.yml && \
    echo '  maxThumbnailSize: 1048576' >> /conf/config.template.yml && \
    echo '' >> /conf/config.template.yml && \
    echo 'development:' >> /conf/config.template.yml && \
    echo '  enableDebugLogging: true' >> /conf/config.template.yml && \
    echo '  isDebug: true' >> /conf/config.template.yml

# Create entrypoint script for Complement
RUN echo '#!/bin/sh' > /app/entrypoint.sh && \
    echo 'set -x' >> /app/entrypoint.sh && \
    echo '' >> /app/entrypoint.sh && \
    echo '# Set default server name if not provided' >> /app/entrypoint.sh && \
    echo 'export SERVER_NAME=${SERVER_NAME:-localhost}' >> /app/entrypoint.sh && \
    echo '' >> /app/entrypoint.sh && \
    echo '# Generate configuration from template with environment variables' >> /app/entrypoint.sh && \
    echo 'envsubst < /conf/config.template.yml > /app/config.yml' >> /app/entrypoint.sh && \
    echo '' >> /app/entrypoint.sh && \
    echo 'echo "🚀 Starting FERRETCANNON Matrix Server for Complement testing"' >> /app/entrypoint.sh && \
    echo 'echo "Server Name: ${SERVER_NAME}"' >> /app/entrypoint.sh && \
    echo 'echo "Port: 8008"' >> /app/entrypoint.sh && \
    echo 'echo ""' >> /app/entrypoint.sh && \
    echo 'echo "=== Config file contents ==="' >> /app/entrypoint.sh && \
    echo 'cat /app/config.yml' >> /app/entrypoint.sh && \
    echo 'echo ""' >> /app/entrypoint.sh && \
    echo 'echo "=== Checking application files ==="' >> /app/entrypoint.sh && \
    echo 'ls -la /app/bin/ || echo "No bin directory"' >> /app/entrypoint.sh && \
    echo 'ls -la /app/lib/ | head -20 || echo "No lib directory"' >> /app/entrypoint.sh && \
    echo 'echo ""' >> /app/entrypoint.sh && \
    echo 'echo "=== Java version ==="' >> /app/entrypoint.sh && \
    echo 'java -version' >> /app/entrypoint.sh && \
    echo 'echo ""' >> /app/entrypoint.sh && \
    echo 'echo "=== Starting server binary ==="' >> /app/entrypoint.sh && \
    echo '' >> /app/entrypoint.sh && \
    echo '# Start the server - dont use exec so we can see errors' >> /app/entrypoint.sh && \
    echo '/app/bin/FERRETCANNON' >> /app/entrypoint.sh && \
    echo 'EXIT_CODE=$?' >> /app/entrypoint.sh && \
    echo 'echo ""' >> /app/entrypoint.sh && \
    echo 'echo "=== Server exited with code: ${EXIT_CODE} ==="' >> /app/entrypoint.sh && \
    echo 'exit ${EXIT_CODE}' >> /app/entrypoint.sh && \
    chmod +x /app/entrypoint.sh

# Install envsubst for environment variable substitution
RUN apk add --no-cache gettext

# Expose the Matrix client-server API port (Complement expects 8008)
EXPOSE 8008

# Health check for Complement to verify server is ready
# Complement uses this to wait for the server to be ready before running tests
HEALTHCHECK --interval=5s --timeout=3s --retries=20 \
    CMD wget -q --spider http://localhost:8008/_matrix/client/versions || exit 1

# Set environment variables
ENV JAVA_OPTS="-Xmx512m -Xms256m"
ENV SERVER_NAME="localhost"

# Use the entrypoint script
ENTRYPOINT ["/app/entrypoint.sh"]
