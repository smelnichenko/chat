# Build stage
FROM eclipse-temurin:25.0.5_8-jdk@sha256:884a2f8692cc1e2c7e21f95914adf0ae0eafee9a7eb663a12169cc44468256ec AS builder

WORKDIR /app

COPY gradle gradle
COPY gradlew build.gradle settings.gradle ./

RUN ./gradlew dependencies --no-daemon

COPY src src

RUN ./gradlew bootJar --no-daemon -x test

# Runtime stage
FROM eclipse-temurin:25.0.5_8-jre@sha256:945247f9d092703e0c6be92243ef3c51640229e23ab34d353c8dcdc73e6cd4e6

WORKDIR /app

# Liquibase 4.30+ sends product-usage analytics to liquibase.com on every start unless told not to
ENV LIQUIBASE_ANALYTICS_ENABLED=false

RUN groupadd -r app && useradd -r -g app app

COPY --from=builder /app/build/libs/*.jar chat.jar

RUN mkdir -p /app/config && chown -R app:app /app

USER app

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar chat.jar"]
