# Multi-stage Docker build for LocalData Managed Redis Platform
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app

# Cache dependencies
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Build application
COPY src ./src
RUN mvn clean package -DskipTests

# Runtime container
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Security: non-root user
RUN addgroup -S localdata && adduser -S localdata -G localdata
USER localdata

# Copy shaded fat jar
COPY --from=build /app/target/mini-redis-1.0-SNAPSHOT-all.jar app.jar

# Environment configuration defaults
ENV HTTP_PORT=8080 \
    REDIS_PORT=6379 \
    BIND_ADDRESS=0.0.0.0 \
    DATA_DIR=/app/data \
    AUTH_PEPPER=localdata-secure-production-pepper-change-me

# Expose HTTP REST / Dashboard and RESP TCP ports
EXPOSE 8080 6379

VOLUME ["/app/data"]

HEALTHCHECK --interval=10s --timeout=3s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://127.0.0.1:8080/health || exit 1

ENTRYPOINT ["java", "-XX:+UseZGC", "-XX:+ZGenerational", "-Xms256m", "-Xmx2g", "-jar", "app.jar"]
