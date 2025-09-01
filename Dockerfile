# Multi-stage build for FERRETCANNON Matrix Server

# Stage 1: Build stage
FROM openjdk:17-jdk-slim as builder

# Set the working directory
WORKDIR /app

# Copy the Gradle wrapper and build files
COPY gradlew* build.gradle.kts settings.gradle.kts gradle.properties* ./
COPY gradle/ gradle/

# Copy the source code
COPY src/ src/

# Make the Gradle wrapper executable
RUN chmod +x gradlew

# Build the application
RUN ./gradlew installDist --no-daemon -x test --no-configuration-cache

# Stage 2: Runtime stage
FROM openjdk:17-jdk-slim

# Set the working directory
WORKDIR /app

# Copy the built application from the builder stage
COPY --from=builder /app/build/install/FERRETCANNON /app/

# Copy configuration file (production version)
COPY config.prod.yml config.yml

# Create directory for persistent data
RUN mkdir -p /data

# Expose the port the app runs on
EXPOSE 8080

# Set environment variables for production
ENV JAVA_OPTS="-Xmx512m -Xms256m"

# Force clean build - version 3
# Create a startup script to handle database location
RUN echo '#!/bin/bash\n\
# Copy database to persistent volume if it exists\n\
if [ -f /data/ferretcannon.db ]; then\n\
  cp /data/ferretcannon.db /app/ferretcannon.db 2>/dev/null || true\n\
fi\n\
\n\
# Run the application (fixed executable name - version 3)\n\
./bin/FERRETCANNON\n\
\n\
# Copy database back to persistent volume after shutdown\n\
cp /app/ferretcannon.db /data/ferretcannon.db 2>/dev/null || true\n\
' > /app/start.sh && chmod +x /app/start.sh

# Run the application
CMD ["/app/start.sh"]
