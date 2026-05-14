# ================================
# Stage 1: Build (Java 21 LTS + Maven) — matches pom.xml java.version=21
# ================================
FROM maven:3.9.9-eclipse-temurin-21-alpine AS builder

WORKDIR /app

# Кэш зависимостей: сначала pom.xml
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Сборка
COPY src ./src
RUN mvn clean package -DskipTests -B

# ================================
# Stage 2: Runtime (минимальный JRE 21)
# ================================
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

RUN apk add --no-cache wget \
    && addgroup -S appgroup && adduser -S appuser -G appgroup

COPY --from=builder /app/target/*.jar app.jar
RUN chown appuser:appgroup app.jar

USER appuser
EXPOSE 8080

# JVM-flags для контейнера: динамическая память + быстрый старт
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC"

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
