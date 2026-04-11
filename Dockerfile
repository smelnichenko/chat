# Build stage
FROM eclipse-temurin:25-jdk AS builder

WORKDIR /app

COPY gradle gradle
COPY gradlew build.gradle settings.gradle ./

RUN ./gradlew dependencies --no-daemon

COPY src src

RUN ./gradlew bootJar --no-daemon -x test

# CDS training stage
FROM eclipse-temurin:25-jre AS cds

WORKDIR /app
COPY --from=builder /app/build/libs/*.jar chat.jar

RUN java -Djarmode=tools -jar chat.jar extract --destination extracted
RUN java -XX:ArchiveClassesAtExit=extracted/application.jsa \
    -Dspring.context.exit=onRefresh \
    -jar extracted/chat.jar || true

# Runtime stage
FROM eclipse-temurin:25-jre

WORKDIR /app

RUN groupadd -r app && useradd -r -g app app

COPY --from=cds /app/extracted /app

RUN mkdir -p /app/config && chown -R app:app /app

USER app

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -XX:SharedArchiveFile=application.jsa -jar chat.jar"]
