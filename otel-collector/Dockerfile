# HomeHub OpenTelemetry Collector
#
# Receives OTLP from the Spring Boot API (gRPC :4317) and the React app
# (HTTP :4318), and writes traces, metrics and logs as OTLP JSON Lines files
# under /telemetry — bind-mount the project's ./telemetry folder there.
#
# You normally don't build this by hand: scripts/otel-env.sh (used by
# `make run` and `make test-functional`) builds it when the image is missing
# or when this folder changed. To build it explicitly:  make collector
#
# Note: the contrib image is required (the `file` exporter is not in the core
# image). It is built FROM scratch: there is no shell, so RUN, mkdir, and
# curl-based HEALTHCHECKs are not possible here. Create the telemetry
# subfolders on the host, and check health from the host via :13133.

# Pin a specific version. Check the latest at
# https://github.com/open-telemetry/opentelemetry-collector-releases/releases
# and the contrib changelog (otlp receiver, file exporter, batch /
# memory_limiter / filter processors, health_check) before changing it.
ARG OTELCOL_VERSION=0.161.0
FROM otel/opentelemetry-collector-contrib:${OTELCOL_VERSION}

ARG OTELCOL_VERSION
# Hash of this folder's Dockerfile + config, set by scripts/otel-env.sh, so
# the script can tell when the image is out of date and rebuild it.
ARG HOMEHUB_CONFIG_SHA=unknown
LABEL org.opencontainers.image.title="homehub-otel" \
      org.opencontainers.image.description="HomeHub OTel Collector: OTLP in, JSONL files out" \
      homehub.otelcol.version="${OTELCOL_VERSION}" \
      homehub.config.sha="${HOMEHUB_CONFIG_SHA}"

# Bake the HomeHub pipeline config into the image. The contrib image's
# default config path is /etc/otelcol-contrib/config.yaml.
COPY otel-collector.yaml /etc/otelcol-contrib/config.yaml

# 4317  OTLP gRPC   (backend)
# 4318  OTLP HTTP   (browser / React app)
# 13133 health_check extension
EXPOSE 4317 4318 13133

# Telemetry output. Must be bind-mounted from the host and writable by the
# image's non-root user.
VOLUME ["/telemetry"]

# ENTRYPOINT/CMD are inherited from the base image
# (/otelcol-contrib --config /etc/otelcol-contrib/config.yaml).
