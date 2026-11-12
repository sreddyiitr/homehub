#!/usr/bin/env bash
# Start the emulator + OTel collector (if needed), export credentials and
# OTel config, then run the API on the 'local' profile WITH the OTel Java
# agent. Bootstrap + seeding run automatically at app startup.
set -euo pipefail
cd "$(dirname "$0")"

source scripts/emulator-env.sh
source scripts/otel-env.sh
source scripts/java-env.sh

cat <<EOF
HomeHub (local)
  API         http://localhost:8080
  Swagger UI  http://localhost:8080/swagger-ui.html
  Readiness   http://localhost:8080/actuator/health/readiness
  Emulator    $AWS_ENDPOINT_URL (container ${EMULATOR_CONTAINER})
  Telemetry   ./telemetry/{traces,metrics,logs}/*.jsonl  (docker logs -f homehub-otel)
  UI (dev)    make frontend-dev  ->  http://localhost:5173
EOF

cd backend
# spring-boot:run forks a JVM; the agent attaches to THAT JVM, and the
# OTEL_* env vars exported above are inherited by it.
exec mvn -q -B spring-boot:run -Dspring-boot.run.jvmArguments="-javaagent:$OTEL_JAVAAGENT_JAR"
