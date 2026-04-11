# Build stage
FROM eclipse-temurin:25-jdk AS builder

WORKDIR /app

COPY gradle gradle
COPY gradlew build.gradle settings.gradle ./

RUN ./gradlew dependencies --no-daemon

COPY src src

RUN ./gradlew bootJar --no-daemon -x test

# Runtime stage
FROM eclipse-temurin:25-jre

WORKDIR /app

RUN groupadd -r app && useradd -r -g app app

COPY --from=builder /app/build/libs/*.jar app.jar

RUN mkdir -p /app/config && chown -R app:app /app

USER app

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
