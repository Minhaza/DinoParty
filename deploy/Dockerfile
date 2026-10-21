# syntax=docker/dockerfile:1
# Single-origin image (decision D49): the Spring Boot backend also serves the built frontend, so
# /api, /ws and the SPA share one origin. Build from the repository root:
#   docker build -f deploy/Dockerfile -t dinoparty .

FROM node:22-alpine AS frontend
WORKDIR /src/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

FROM eclipse-temurin:21-jdk AS backend
WORKDIR /src/backend
COPY backend/mvnw backend/pom.xml ./
COPY backend/.mvn .mvn
# A Windows checkout may hand the wrapper over with CRLF endings.
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw && ./mvnw -B -ntp -q dependency:go-offline
COPY backend/src src
# Tests run in CI (Testcontainers needs Docker, which a build does not have).
RUN ./mvnw -B -ntp -q -DskipTests package \
 && cp target/dinoparty-backend-*.jar /src/app.jar

FROM eclipse-temurin:21-jre
RUN groupadd --system app && useradd --system --gid app --no-create-home app
WORKDIR /app
COPY --from=backend /src/app.jar app.jar
COPY --from=frontend /src/frontend/dist static/
ENV SPA_ENABLED=true \
    SPA_LOCATION=file:/app/static/ \
    # Small hosts (Render free: 512 MB, a fraction of a CPU): serial GC, heap within the limit,
    # and exit on OOM so the platform restarts the process instead of leaving it half-alive.
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError -Xss512k"
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
