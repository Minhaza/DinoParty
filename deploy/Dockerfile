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

# Temurin's JRE image leaves out jdk.random, which SeededRandomSource needs (L64X128MixRandom), so
# build a runtime with exactly the JRE's modules plus jdk.random and fail the build without it.
FROM eclipse-temurin:21-jre AS jre-modules
RUN java --list-modules | sed 's/@.*//' | paste -sd, - > /jre-modules

FROM eclipse-temurin:21-jdk AS runtime
COPY --from=jre-modules /jre-modules /jre-modules
RUN jlink --add-modules "$(cat /jre-modules),jdk.random" --strip-debug --no-man-pages \
      --no-header-files --output /runtime \
 && /runtime/bin/java --list-modules | grep -q '^jdk.random@'

# Same Ubuntu release as the Temurin images, without their JRE (a layer that only deletes it would
# still ship it). The app needs CA certificates and time zone data, nothing else from the OS.
FROM ubuntu:26.04
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates tzdata \
 && rm -rf /var/lib/apt/lists/*
ENV JAVA_HOME=/opt/java/openjdk \
    LANG=C.UTF-8
ENV PATH="${JAVA_HOME}/bin:${PATH}"
COPY --from=runtime /runtime /opt/java/openjdk
RUN groupadd --system app && useradd --system --gid app --no-create-home app
WORKDIR /app
COPY --from=backend /src/app.jar app.jar
COPY --from=frontend /src/frontend/dist static/
# Small hosts (Render free: 512 MB, a fraction of a CPU): serial GC, heap within the limit, and
# exit on OOM so the platform restarts the process instead of leaving it half-alive.
ENV SPA_ENABLED=true \
    SPA_LOCATION=file:/app/static/ \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError -Xss512k"
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
