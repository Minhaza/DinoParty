#!/usr/bin/env bash
# Starts PostgreSQL, backend and frontend. Ctrl+C stops backend and frontend.
# Usage: scripts/dev.sh [--observability] [--stop]
set -euo pipefail
cd "$(dirname "$0")/.."

[ -f .env ] || { cp .env.example .env; echo "Created .env from .env.example"; }
compose=(docker compose --env-file .env -f deploy/compose.dev.yml)
obs=(-f deploy/compose.observability.yml)

services=(db)
for arg in "$@"; do
  case "$arg" in
    --stop) "${compose[@]}" "${obs[@]}" stop; exit ;;
    --observability) compose+=("${obs[@]}"); services+=(otel-collector aspire-dashboard); export OTEL_ENABLED=true VITE_OTEL_ENABLED=true ;;
    *) echo "Unknown option: $arg" >&2; exit 2 ;;
  esac
done

# Backend is compiled for Java 21; an older JDK fails with UnsupportedClassVersionError.
if ! "${JAVA_HOME:+$JAVA_HOME/bin/}java" -version 2>&1 | grep -q 'version "21\.'; then
  echo "JDK 21 required: set JAVA_HOME to a JDK 21 (current: ${JAVA_HOME:-unset})" >&2; exit 1
fi
[ -d frontend/node_modules ] || npm --prefix frontend ci
"${compose[@]}" up -d --wait "${services[@]}"

trap 'kill 0' EXIT
./backend/mvnw -f backend spring-boot:run -Dspring-boot.run.profiles=dev &
npm --prefix frontend run dev &
echo -e "\nApp: http://localhost:5173  Backend: http://localhost:8080/api/v1/health"
wait -n
