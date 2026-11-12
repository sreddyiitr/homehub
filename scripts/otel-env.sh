#!/usr/bin/env bash
# Source this file:  source scripts/otel-env.sh   (from the repo root)
#
# 1. Downloads the OpenTelemetry Java agent into otel/ (gitignored) when it's
#    missing or the wrong version, and verifies its SHA-512 checksum. The
#    version comes from backend/pom.xml (<otel.agent.version>) — the ONLY
#    place it is set, so the agent and the app's OTel API can't drift apart.
# 2. Builds the collector image from ./otel-collector/ when it's missing or
#    that folder changed, and ensures the collector container is running
#    (on the current image) and writing to THIS repo's telemetry/ folder.
# 3. Exports the OTEL_* env vars the agent reads. The agent is configured
#    ONLY through these — never in Java code.
#
# Every exporter/protocol setting is explicit on purpose: defaults have
# changed between agent majors (e.g. the OTLP protocol default is
# http/protobuf on :4318, but our backend contract is gRPC on :4317).

OTEL_AGENT_JAR="$PWD/otel/opentelemetry-javaagent.jar"
OTEL_COLLECTOR_IMAGE="homehub-otel:local"
OTEL_COLLECTOR_CONTAINER="homehub-otel"
OTEL_COLLECTOR_DIR="$PWD/otel-collector"

_otel_fail() { echo "otel-env: $*" >&2; return 1; }

# Agent version wanted: <otel.agent.version> in backend/pom.xml.
_otel_wanted_version() {
  sed -n 's:.*<otel.agent.version>\(.*\)</otel.agent.version>.*:\1:p' "$PWD/backend/pom.xml" | head -1
}

# Version of the jar in otel/, read from the jar's own manifest
# (Implementation-Version). Works for a jar downloaded by hand, too.
_otel_present_version() {
  [ -f "$OTEL_AGENT_JAR" ] || return 0
  unzip -p "$OTEL_AGENT_JAR" META-INF/MANIFEST.MF 2>/dev/null | tr -d '\r' \
    | sed -n 's/^Implementation-Version: //p' | head -1
}

_otel_download_agent() {
  OTEL_AGENT_VERSION="$(_otel_wanted_version)"
  [ -n "$OTEL_AGENT_VERSION" ] || { _otel_fail "no <otel.agent.version> in backend/pom.xml"; return 1; }
  rm -f "$PWD/otel/.version"   # marker file from an older version of this script
  [ "$(_otel_present_version)" = "$OTEL_AGENT_VERSION" ] && return 0

  mkdir -p "$PWD/otel"
  local url="https://repo1.maven.org/maven2/io/opentelemetry/javaagent/opentelemetry-javaagent/$OTEL_AGENT_VERSION/opentelemetry-javaagent-$OTEL_AGENT_VERSION.jar"
  echo "otel-env: downloading OTel Java agent $OTEL_AGENT_VERSION into otel/ (one time, ~26 MB)" >&2
  if ! curl -sfL --connect-timeout 15 "$url" -o "$OTEL_AGENT_JAR.tmp"; then
    rm -f "$OTEL_AGENT_JAR.tmp"
    _otel_fail "could not download the OTel Java agent from Maven Central.
    Behind a proxy? Set HTTPS_PROXY and retry. Or download it yourself and re-run:
      curl -L -o otel/opentelemetry-javaagent.jar $url
    (or save that URL with a browser as otel/opentelemetry-javaagent.jar)"
    return 1
  fi
  local expected actual
  expected="$(curl -sfL --connect-timeout 15 "$url.sha512" | awk '{print $1}')"
  actual="$(shasum -a 512 "$OTEL_AGENT_JAR.tmp" | awk '{print $1}')"
  if [ -z "$expected" ] || [ "$expected" != "$actual" ]; then
    rm -f "$OTEL_AGENT_JAR.tmp"
    _otel_fail "checksum mismatch for the downloaded agent jar (corrupted download, or a proxy altered it)"; return 1
  fi
  mv "$OTEL_AGENT_JAR.tmp" "$OTEL_AGENT_JAR"
}

# Hash of the collector's build inputs. Stored as an image label, so we can
# tell whether the local image was built from the current config.
_otel_collector_sha() {
  cat "$OTEL_COLLECTOR_DIR/Dockerfile" "$OTEL_COLLECTOR_DIR/otel-collector.yaml" | shasum -a 256 | cut -c1-16
}

_otel_build_collector() {
  local want have
  want="$(_otel_collector_sha)"
  have="$(docker image inspect -f '{{index .Config.Labels "homehub.config.sha"}}' "$OTEL_COLLECTOR_IMAGE" 2>/dev/null)"
  [ "$have" = "$want" ] && return 0
  if [ -n "$have" ]; then
    echo "otel-env: otel-collector/ changed — rebuilding $OTEL_COLLECTOR_IMAGE" >&2
  else
    echo "otel-env: building $OTEL_COLLECTOR_IMAGE from otel-collector/ (the first build downloads the collector base image)" >&2
  fi
  # Validate first: a broken config would otherwise only show up as a
  # collector that exits right after starting.
  local version
  version="$(sed -n 's/^ARG OTELCOL_VERSION=//p' "$OTEL_COLLECTOR_DIR/Dockerfile")"
  docker run --rm -v "$OTEL_COLLECTOR_DIR/otel-collector.yaml:/cfg.yaml:ro" \
    "otel/opentelemetry-collector-contrib:$version" validate --config=/cfg.yaml >&2 \
    || { _otel_fail "otel-collector/otel-collector.yaml is invalid (see above)"; return 1; }
  docker build -q --build-arg "HOMEHUB_CONFIG_SHA=$want" \
    -t "$OTEL_COLLECTOR_IMAGE" -t "homehub-otel:$version" "$OTEL_COLLECTOR_DIR" >/dev/null \
    || { _otel_fail "docker build failed (run: docker build $OTEL_COLLECTOR_DIR)"; return 1; }
}

_otel_ensure_collector() {
  _otel_build_collector || return 1

  # The image has no shell, so we create the output folders ourselves.
  mkdir -p telemetry/traces telemetry/metrics telemetry/logs

  if [ "$(docker inspect -f '{{.State.Running}}' "$OTEL_COLLECTOR_CONTAINER" 2>/dev/null)" = "true" ]; then
    # Reuse only if it writes into THIS repo's telemetry/ ...
    local src
    src="$(docker inspect -f '{{range .Mounts}}{{if eq .Destination "/telemetry"}}{{.Source}}{{end}}{{end}}' "$OTEL_COLLECTOR_CONTAINER")"
    if [ "$src" != "$PWD/telemetry" ]; then
      _otel_fail "$OTEL_COLLECTOR_CONTAINER is running but writes to '$src', not '$PWD/telemetry'. Stop it first: docker stop $OTEL_COLLECTOR_CONTAINER"
      return 1
    fi
    # ... and runs the current image; otherwise replace it (it only holds
    # telemetry that is already in the files, so nothing is lost).
    if [ "$(docker inspect -f '{{.Image}}' "$OTEL_COLLECTOR_CONTAINER")" != "$(docker image inspect -f '{{.Id}}' "$OTEL_COLLECTOR_IMAGE")" ]; then
      echo "otel-env: $OTEL_COLLECTOR_CONTAINER runs an older image — restarting it on the new one" >&2
      docker rm -f "$OTEL_COLLECTOR_CONTAINER" >/dev/null
    fi
  fi

  if [ "$(docker inspect -f '{{.State.Running}}' "$OTEL_COLLECTOR_CONTAINER" 2>/dev/null)" != "true" ]; then
    docker rm -f "$OTEL_COLLECTOR_CONTAINER" >/dev/null 2>&1 || true
    local port
    for port in 4317 4318 13133; do
      if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
        _otel_fail "port $port is already in use (another collector?). Free it: lsof -nP -iTCP:$port"
        return 1
      fi
    done
    echo "otel-env: starting $OTEL_COLLECTOR_CONTAINER" >&2
    # Published on 127.0.0.1 only: the app and your browser are on this
    # machine, so other computers have no reason to reach the collector.
    docker run -d --name "$OTEL_COLLECTOR_CONTAINER" \
      -p 127.0.0.1:4317:4317 -p 127.0.0.1:4318:4318 -p 127.0.0.1:13133:13133 \
      -v "$PWD/telemetry:/telemetry" \
      "$OTEL_COLLECTOR_IMAGE" >/dev/null || { _otel_fail "docker run failed"; return 1; }
  fi

  local i
  for i in $(seq 1 30); do
    curl -sf localhost:13133 >/dev/null 2>&1 && return 0
    sleep 1
  done
  _otel_fail "collector not healthy after 30s (docker logs $OTEL_COLLECTOR_CONTAINER)"
}

_otel_export_env() {
  local sha
  sha="$(git rev-parse --short HEAD 2>/dev/null || echo unknown)"
  git diff --quiet HEAD 2>/dev/null || sha="$sha-dirty"

  export OTEL_SERVICE_NAME="homehub-api"
  export OTEL_RESOURCE_ATTRIBUTES="deployment.environment=local,service.version=$sha"
  export OTEL_EXPORTER_OTLP_ENDPOINT="http://localhost:4317"
  export OTEL_EXPORTER_OTLP_PROTOCOL="grpc"
  export OTEL_TRACES_EXPORTER="otlp"
  export OTEL_METRICS_EXPORTER="otlp"
  export OTEL_LOGS_EXPORTER="otlp"
  export OTEL_METRIC_EXPORT_INTERVAL="5000"      # 5s so tests see metrics promptly
  export OTEL_PROPAGATORS="tracecontext,baggage" # W3C traceparent end to end
  # Logback → OTel log bridge (on by default in the agent). SLF4J key/value
  # pairs (log.atInfo().addKeyValue("homehub.listing.id", id)) become OTel log
  # attributes — but ONLY keys on this allowlist, so an accidental
  # addKeyValue("email", …) never reaches telemetry (R-PII-01).
  # Verified against agent 2.33.1: this replaces the deprecated
  # logback-appender.experimental.capture-key-value-pair-attributes.
  export OTEL_INSTRUMENTATION_COMMON_LOGGING_STRUCTURED_ATTRIBUTES_INCLUDED="homehub.*"
  export OTEL_JAVAAGENT_JAR="$OTEL_AGENT_JAR"

  # ---- Signal-to-noise: keep USER ACTIONS, drop background chatter --------
  # HOMEHUB_TELEMETRY=verbose restores everything (JVM metrics, framework logs).
  if [ "${HOMEHUB_TELEMETRY:-focused}" = "verbose" ]; then
    export OTEL_INSTRUMENTATION_RUNTIME_TELEMETRY_ENABLED="true"
    export OTEL_EXPORTER_OTLP_METRICS_TEMPORALITY_PREFERENCE="cumulative"
    export HOMEHUB_LOG_LEVEL_FRAMEWORK="INFO"
  else
    # No JVM runtime metrics (heap, GC, threads, CPU every 5s).
    export OTEL_INSTRUMENTATION_RUNTIME_TELEMETRY_ENABLED="false"
    # Delta temporality: a counter/histogram is exported only for intervals
    # in which something was recorded — an idle app writes no metric points.
    # (Cumulative, the default, re-exports every series every interval.)
    export OTEL_EXPORTER_OTLP_METRICS_TEMPORALITY_PREFERENCE="delta"
    # Spring/Tomcat INFO chatter off; their WARN/ERROR still flow. Read by
    # application.yml (logging.level.root); com.homehub stays at INFO.
    export HOMEHUB_LOG_LEVEL_FRAMEWORK="WARN"
  fi
}

_otel_download_agent && _otel_ensure_collector && _otel_export_env && \
  echo "otel-env: ready — agent $OTEL_AGENT_VERSION, collector healthy, service.version=${OTEL_RESOURCE_ATTRIBUTES##*=}" >&2
