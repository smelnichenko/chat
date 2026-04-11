# Runtime-only Dockerfile - expects pre-built JAR
# Used for fast deployment when artifacts are built locally
FROM eclipse-temurin:25-jre

WORKDIR /app

# Create non-root user
RUN groupadd -r app && useradd -r -g app app

# Copy the pre-built jar (must be provided at build context)
COPY app.jar app.jar

# Create config directory
RUN mkdir -p /app/config && chown -R app:app /app

USER app

EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
