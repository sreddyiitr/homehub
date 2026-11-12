#!/usr/bin/env bash
# Source this file:  source scripts/emulator-env.sh
#
# Finds (or starts) an AWS Emulator container, then exports the env vars
# every tool (AWS SDK, CLI, our app, tests) needs to talk to it.
#
# Works for anyone on the team with NO edits. Which emulator is used:
#   1. EMULATOR_CONTAINER=<name> set      -> that container (explicit choice)
#   2. exactly one running container from $EMULATOR_IMAGE -> reuse it,
#      whatever its name or port (e.g. one you started by hand)
#   3. several running                    -> stop and ask you to pick (1.)
#   4. none running                       -> start our own: name
#      "homehub-emulator", port $EMULATOR_PORT, telemetry off, labelled
#      homehub.managed=true so `make down` only ever removes THAT one.
# No container name or port is hardcoded for reuse — everything is read
# from `docker port` / `docker inspect`.
#
# Optional overrides (env vars):
#   EMULATOR_IMAGE      image to look for / start   (default aws-emulator:latest)
#   EMULATOR_CONTAINER  use this container          (default: auto-detect)
#   EMULATOR_PORT       port when WE start one      (default 9297, the emulator's default)
#
# Follows the official guide (keep in sync when it changes):
#   Docker:      https://docs.aws.amazon.com/emulator/latest/userguide/getting-started-docker.html
#   SDK/CLI env: https://docs.aws.amazon.com/emulator/latest/userguide/point-sdk-to-emulator.html
#   Telemetry:   https://docs.aws.amazon.com/emulator/latest/userguide/telemetry.html
#
# Why each piece exists (see specs/decisions.md V-1..V-3, V-15):
#  - The emulator writes NEW credentials on every start to
#    <its HOME>/.aws/emulator/<port>/emulator_credentials. That folder must
#    be bind-mounted to the host so we can read it (we find the host side
#    from the container's mounts).
#  - It serves HTTPS with a per-run CA (ca.pem in the same folder). We pass
#    the CA path to the app; it is trusted only by the AWS client.
#  - Regions are isolated and the generated config defaults to us-east-1,
#    so AWS_REGION is set explicitly.

EMULATOR_IMAGE="${EMULATOR_IMAGE:-aws-emulator:latest}"
EMULATOR_PORT="${EMULATOR_PORT:-9297}"
HOMEHUB_EMULATOR_NAME="homehub-emulator"   # only used when WE start one
HOMEHUB_MANAGED_LABEL="homehub.managed=true"

_hh_fail() { echo "emulator-env: $*" >&2; return 1; }
_hh_running() { [ "$(docker inspect -f '{{.State.Running}}' "$1" 2>/dev/null)" = "true" ]; }

# Sets EMULATOR_CONTAINER to the container to use (starting one if needed).
_hh_pick_container() {
  if [ -n "${EMULATOR_CONTAINER:-}" ]; then
    _hh_running "$EMULATOR_CONTAINER" || { _hh_fail "EMULATOR_CONTAINER=$EMULATOR_CONTAINER is not running"; return 1; }
    return 0
  fi

  # `ancestor` matches containers created from this image (any tag given).
  local found count
  found="$(docker ps --filter "ancestor=$EMULATOR_IMAGE" --format '{{.Names}}')"
  count="$(printf '%s' "$found" | grep -c . || true)"
  if [ "$count" -eq 1 ]; then
    EMULATOR_CONTAINER="$found"
    return 0
  fi
  if [ "$count" -gt 1 ]; then
    _hh_fail "several emulator containers are running — choose one with EMULATOR_CONTAINER=<name>:
$(printf '%s\n' "$found" | sed 's/^/    /')"
    return 1
  fi
  _hh_start_own
}

_hh_start_own() {
  docker image inspect "$EMULATOR_IMAGE" >/dev/null 2>&1 || {
    _hh_fail "image $EMULATOR_IMAGE not found locally (set EMULATOR_IMAGE, or pull/build it first)"; return 1; }
  if lsof -nP -iTCP:"$EMULATOR_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
    _hh_fail "port $EMULATOR_PORT is in use by something that isn't an $EMULATOR_IMAGE container (set EMULATOR_PORT)"
    return 1
  fi
  docker rm -f "$HOMEHUB_EMULATOR_NAME" >/dev/null 2>&1 || true
  # Create the shared folder first: on Linux, Docker would create it owned
  # by root and the SDK couldn't read it.
  mkdir -p "$HOME/.aws/emulator"
  echo "emulator-env: no running emulator found — starting $HOMEHUB_EMULATOR_NAME on 127.0.0.1:$EMULATOR_PORT (telemetry off)" >&2
  # Inside the container it must bind 0.0.0.0 so Docker's port mapping can
  # reach it; on the host we publish ONLY on 127.0.0.1. HOME + the bind mount
  # make the credentials file land on the host at the same path.
  # As in the Docker guide: memory capped at 2 GiB without swap (over the cap
  # the container exits with 137 and in-memory resources are lost), --rm so
  # stopping it removes it, and SAME port on both sides of -p (the emulator
  # writes its port into the files it creates). -b 0.0.0.0 is already in the
  # documented image's ENTRYPOINT; repeating it keeps other images working.
  # On Linux, --user makes the shared files belong to you, not root.
  local user_flag=()
  [ "$(uname -s)" = "Linux" ] && user_flag=(--user "$(id -u):$(id -g)")
  docker run -d --rm --name "$HOMEHUB_EMULATOR_NAME" --label "$HOMEHUB_MANAGED_LABEL" "${user_flag[@]}" \
    --memory=2g --memory-swap=2g \
    -p "127.0.0.1:$EMULATOR_PORT:$EMULATOR_PORT" \
    -e HOME="$HOME" -e EMULATOR_TELEMETRY=off \
    -v "$HOME/.aws/emulator:$HOME/.aws/emulator" \
    "$EMULATOR_IMAGE" -b 0.0.0.0 -p "$EMULATOR_PORT" --telemetry off >/dev/null \
    || { _hh_fail "docker run failed"; return 1; }
  EMULATOR_CONTAINER="$HOMEHUB_EMULATOR_NAME"
}

# Reads ports and the host credentials folder from the container itself.
_hh_inspect_container() {
  # `docker port` prints e.g. "9297/tcp -> 127.0.0.1:9297". The left side is
  # the emulator's own port (it names the credentials folder); the right side
  # is where the host reaches it (it goes into the endpoint URL).
  local mapping
  mapping="$(docker port "$EMULATOR_CONTAINER" 2>/dev/null | grep '/tcp' | head -1)"
  [ -n "$mapping" ] || { _hh_fail "$EMULATOR_CONTAINER publishes no port (start it with -p 127.0.0.1:<port>:<port>)"; return 1; }
  EMULATOR_INTERNAL_PORT="${mapping%%/tcp*}"
  EMULATOR_HOST_PORT="${mapping##*:}"
  if [ "$EMULATOR_INTERNAL_PORT" != "$EMULATOR_HOST_PORT" ]; then
    # Works for our scripts (we build the endpoint from the host port), but
    # the emulator's own config file names the container port, and two such
    # containers would share ~/.aws/emulator/<port>/ and delete each other's
    # files. The Docker guide says to use the same port on both sides.
    echo "emulator-env: note — $EMULATOR_CONTAINER maps host port $EMULATOR_HOST_PORT to container port $EMULATOR_INTERNAL_PORT; use the same port on both sides" >&2
  fi
  case "$mapping" in *"-> 0.0.0.0:"*|*"-> [::]:"*)
    echo "emulator-env: note — $EMULATOR_CONTAINER is published on ALL interfaces; prefer -p 127.0.0.1:<port>:<port>" >&2 ;;
  esac

  # Host side of the mount whose container path ends in /.aws/emulator.
  local src
  src="$(docker inspect -f '{{range .Mounts}}{{.Source}}|{{.Destination}}{{"\n"}}{{end}}' "$EMULATOR_CONTAINER" \
        | awk -F'|' '$2 ~ /\/\.aws\/emulator\/?$/ {print $1; exit}')"
  if [ -z "$src" ]; then
    _hh_fail "$EMULATOR_CONTAINER doesn't mount its credentials folder to the host. Recreate it with:
    -e HOME=\"\$HOME\" -v \"\$HOME/.aws/emulator:\$HOME/.aws/emulator\""
    return 1
  fi
  EMULATOR_STATE_DIR="$src/$EMULATOR_INTERNAL_PORT"
}

_hh_wait_healthy() {
  local i
  for i in $(seq 1 60); do
    if [ -f "$EMULATOR_STATE_DIR/ca.pem" ] && \
       curl -sf --cacert "$EMULATOR_STATE_DIR/ca.pem" \
         "https://127.0.0.1:$EMULATOR_HOST_PORT/__admin/health" >/dev/null 2>&1; then
      return 0
    fi
    sleep 0.5
  done
  _hh_fail "emulator not healthy after 30s (docker logs $EMULATOR_CONTAINER)"
}

# CLAUDE.md prefers emulator usage telemetry OFF. A container we didn't start
# keeps its owner's setting; we only report it (the emulator logs its state).
_hh_report_telemetry() {
  local line
  line="$(docker logs "$EMULATOR_CONTAINER" 2>&1 | grep -m1 -i 'usage and error telemetry' || true)"
  case "$line" in
    *": enabled"*) echo "emulator-env: note — usage telemetry is ON in $EMULATOR_CONTAINER (its owner's choice; restart it with --telemetry off to opt out)" >&2 ;;
  esac
}

_hh_export_env() {
  local creds="$EMULATOR_STATE_DIR/emulator_credentials"
  [ -f "$creds" ] || { _hh_fail "missing $creds (an emulator started with --auth off writes none; HomeHub needs auth on)"; return 1; }
  # No check of the key FORMAT: the guide says it can change in any release
  # ("Don't validate them in your code"). Presence is all we need.
  # The variables from "To point your SDK at a local emulator" in the guide.
  # Credentials come from the emulator's files (not copied into env vars),
  # and AWS_CONFIG_FILE also keeps your personal ~/.aws/config out of it.
  export AWS_ENDPOINT_URL="https://127.0.0.1:$EMULATOR_HOST_PORT"
  export AWS_SHARED_CREDENTIALS_FILE="$creds"
  export AWS_CONFIG_FILE="$EMULATOR_STATE_DIR/config"
  export AWS_CA_BUNDLE="$EMULATOR_STATE_DIR/ca.pem"
  export AWS_EC2_METADATA_DISABLED="true"           # never fall back to EC2 instance credentials
  export AWS_DISABLE_HOST_PREFIX_INJECTION="true"   # CLI/boto3: keep requests on 127.0.0.1
  # The guide's example uses us-east-1 (the emulator config's default); we
  # use us-west-2 everywhere — regions are isolated, so be consistent.
  export AWS_REGION="us-west-2" AWS_DEFAULT_REGION="us-west-2"
  export EMULATOR_CONTAINER
  # Env-var credentials beat the credentials file, so clear any real ones.
  unset AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN AWS_PROFILE
}

_hh_pick_container && _hh_inspect_container && _hh_wait_healthy && _hh_export_env && {
  _hh_report_telemetry
  echo "emulator-env: ready — $EMULATOR_CONTAINER at $AWS_ENDPOINT_URL region=$AWS_REGION (credentials: ${AWS_SHARED_CREDENTIALS_FILE/#$HOME/~})" >&2
}
