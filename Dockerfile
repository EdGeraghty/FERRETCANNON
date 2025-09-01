# Use OpenJDK 17 as the base image
FROM openjdk:17-jdk-slim

# Set the working directory
WORKDIR /app

# Copy the Gradle wrapper and build files
COPY gradlew gradlew.bat build.gradle.kts settings.gradle.kts gradle.properties ./
COPY gradle/ gradle/

# Copy the source code
COPY src/ src/

# Copy configuration file (production version)
COPY config.prod.yml config.yml

# Make the Gradle wrapper executable
RUN chmod +x gradlew

# Build the application
RUN ./gradlew build --no-daemon

# Create directory for persistent data
RUN mkdir -p /data

# Expose the port the app runs on
EXPOSE 8080

# Set environment variables for production
ENV JAVA_OPTS="-Xmx512m -Xms256m"

# Create a startup script to handle database location
RUN echo '#!/bin/bash\n\
# Copy database to persistent volume if it exists\n\
if [ -f /data/ferretcannon.db ]; then\n\
  cp /data/ferretcannon.db /app/ferretcannon.db 2>/dev/null || true\n\
fi\n\
\n\
# Run the application\n\
./gradlew run --no-daemon\n\
\n\
# Copy database back to persistent volume after shutdown\n\
cp /app/ferretcannon.db /data/ferretcannon.db 2>/dev/null || true\n\
' > /app/start.sh && chmod +x /app/start.sh

# Run the application
CMD ["/app/start.sh"]
