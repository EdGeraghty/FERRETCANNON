# Multi-stage build for FERRETCANNON Matrix Server

# Stage 1: Build stage
FROM eclipse-temurin:17-jdk-alpine as builder

# Set the working directory
WORKDIR /app

# Copy the Gradle wrapper and build files
COPY gradlew build.gradle.kts settings.gradle.kts gradle.properties* ./
COPY gradle/ gradle/

# Copy the source code
COPY src/ src/

# Make the Gradle wrapper executable
RUN chmod +x gradlew

# Build the application
RUN ./gradlew installDist --no-daemon -x test --no-configuration-cache

# Stage 2: Runtime stage
FROM eclipse-temurin:17-jdk-alpine

# Set the working directory
WORKDIR /app

# Install security updates and remove cache (Alpine)
RUN apk update && apk upgrade && rm -rf /var/cache/apk/*

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

# Force clean build - version 4
# Run the application directly
CMD ["sh", "-c", "rm -f /data/ferretcannon.db && ./bin/FERRETCANNON"]
