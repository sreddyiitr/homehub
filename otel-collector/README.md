# OTel Collector (`homehub-otel`)

The OpenTelemetry Collector receives traces, metrics and logs from the app,
and writes them to files in the project's `telemetry/` folder. No vendor
backend is needed: you read the files with `make telemetry`, or open them
yourself.

```
Spring Boot API ──OTLP/gRPC :4317──┐
                                   ├─▶ collector ─▶ telemetry/traces/traces.jsonl
Browser (React) ──OTLP/HTTP :4318──┘   (Docker)     telemetry/metrics/metrics.jsonl
                                                     telemetry/logs/logs.jsonl
```

This folder used to be a separate project (`homehub-otel-collector`). It now
lives here, so the app and its collector change together.

| File | What it is |
|---|---|
| `Dockerfile` | `FROM` the pinned contrib collector image, plus our config. Nothing else: the base image has no shell |
| `otel-collector.yaml` | **The pipeline config**: what is received, filtered and written |

## You usually don't need to do anything

`make run` and `make test-functional` call `scripts/otel-env.sh`, which:

1. **builds** the image `homehub-otel:local` from this folder if it's
   missing, or **rebuilds** it if you edited a file here (it checks the
   config first);
2. creates `telemetry/traces`, `telemetry/metrics` and `telemetry/logs`;
3. **starts** the `homehub-otel` container, or replaces it if it runs an older
   image;
4. waits until the collector reports healthy.

Useful commands (from the project root):

```bash
make collector        # build + (re)start it now, without starting the app
make collector-logs   # live view: one line per batch received
make telemetry        # readable summary of the files (span trees, logs, metric names)
make telemetry-clean  # empty the files (briefly restarts the collector)
```

## Doing it by hand

These are the same steps the script runs. Use them to learn how it works, or
to debug it. Run them **from the project root**, so `$PWD/telemetry` is this
project's folder.

```bash
# 1. Build the image (first build downloads the collector base image)
docker build -t homehub-otel:local otel-collector/

# 2. Create the output folders (the image has no shell, so it can't create them)
mkdir -p telemetry/traces telemetry/metrics telemetry/logs

# 3. Start the collector, writing into THIS project's telemetry/ folder
docker run -d --name homehub-otel \
  -p 127.0.0.1:4317:4317 \
  -p 127.0.0.1:4318:4318 \
  -p 127.0.0.1:13133:13133 \
  -v "$PWD/telemetry:/telemetry" \
  homehub-otel:local

# 4. Check it's healthy
curl -s localhost:13133          # {"status":"Server available",...}
```

What each part of `docker run` does:

| Part | Why |
|---|---|
| `-d` | Runs in the background |
| `--name homehub-otel` | A fixed name, so `docker logs homehub-otel` and `docker stop homehub-otel` work. The scripts look for this name |
| `-p 127.0.0.1:4317:4317` | Where the Spring Boot app sends telemetry (gRPC) |
| `-p 127.0.0.1:4318:4318` | Where the browser sends telemetry (HTTP). CORS allows `localhost` origins only |
| `-p 127.0.0.1:13133:13133` | The health check |
| `127.0.0.1:` on each port | Only your machine can reach it, not your whole network |
| `-v "$PWD/telemetry:/telemetry"` | Connects this project's `telemetry/` folder to `/telemetry` in the container, where the collector writes |

To stop it:

```bash
docker stop homehub-otel && docker rm homehub-otel
```

> **Only one collector can use ports 4317, 4318 and 13133 at a time.** If one
> is already running from somewhere else, the scripts stop with an error
> rather than send your telemetry to the wrong folder. Stop the other one
> first.

## How the collector works (quick tour of `otel-collector.yaml`)

| Section | Role | Ours |
|---|---|---|
| **receivers** | How data comes in | `otlp`: gRPC on 4317, HTTP on 4318 |
| **processors** | What happens to it on the way | `memory_limiter` (protects the collector), `filter/noise` (drops non-user telemetry), `batch` (groups writes, flushed every ~1 s) |
| **exporters** | Where it goes | `file/traces`, `file/metrics`, `file/logs` (OTLP JSON, one object per line, rotated at 10 MB × 5), plus `debug` (one log line per batch) |
| **pipelines** | Wire it together, per signal | traces and metrics: `memory_limiter → filter/noise → batch`; logs: `memory_limiter → batch` |

`filter/noise` keeps the files about **user actions**. It drops:

- the SDK's own self-metrics;
- server spans for `/actuator`, `/v3/api-docs` and `/swagger-ui`;
- browser CORS `OPTIONS` preflights.

## The contract with the app

The app (and its tests) rely on these. If you change one, update
`scripts/otel-env.sh`, the frontend's `VITE_OTEL_COLLECTOR_URL` and
`CLAUDE.md` too.

| Item | Value |
|---|---|
| Image | `homehub-otel:local` (also tagged `homehub-otel:<collector version>`) |
| OTLP gRPC | `localhost:4317` (backend Java agent) |
| OTLP HTTP | `localhost:4318` (browser) |
| Health | `curl -sf localhost:13133` |
| Mount | container `/telemetry` ← project `./telemetry` |
| Files | `telemetry/traces/traces.jsonl`, `telemetry/metrics/metrics.jsonl`, `telemetry/logs/logs.jsonl` |

## Rules when editing

- `memory_limiter` stays **first** in every pipeline.
- Keep the batch timeout and the file `flush_interval` at about 1 s. Tests read
  the files and need prompt writes.
- `debug` stays at `basic` verbosity, so `make collector-logs` is readable.
- CORS allows only `localhost`/`127.0.0.1` origins. Never `*`.
- Receivers bind `0.0.0.0` *inside* the container (Docker can't forward to
  `localhost`); the host side is published on `127.0.0.1` only.
- To upgrade the collector, change `OTELCOL_VERSION` in the `Dockerfile`
  after checking the [releases](https://github.com/open-telemetry/opentelemetry-collector-releases/releases)
  and the contrib changelog for the otlp receiver, file exporter, the
  batch, memory_limiter and filter processors, and health_check.

## Troubleshooting

| Symptom | Fix |
|---|---|
| `otel-collector/otel-collector.yaml is invalid` | The error above it names the problem. Fix the YAML; the running collector is left as it was |
| `port 4317 is already in use` | Another collector is running: `docker ps`, then stop it |
| `homehub-otel is running but writes to '…'` | It was started from another folder. `docker stop homehub-otel`, then `make collector` |
| Files stay empty | Check `curl localhost:13133` and `make collector-logs`. The container runs as a non-root user, so the `telemetry/` folder must be writable |
| Files contain blocks of NUL bytes | Something emptied them while the collector was running. Use `make telemetry-clean`, which stops it first |
