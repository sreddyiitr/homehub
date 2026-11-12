# HomeHub

A small real-estate listings app built to **learn the AWS Emulator**, which
runs AWS APIs (here, DynamoDB) in a Docker container on your laptop. You need
no AWS account and pay nothing, and you can't touch real cloud resources by
accident.

## What it does today

This is the first thin slice of a bigger project. It does one thing end to
end:

> **Browse and search home listings.** Open the web page, filter by city,
> price and bedrooms, page through results, and open a listing's details.

Behind that page:

```
Browser (React, :5173) ──HTTP──▶ Spring Boot API (:8080) ──AWS SDK──▶ AWS Emulator (Docker, :9297)
       │                                │                               └─ DynamoDB table "Listings"
       │ traces                         │ traces, logs, metrics
       ▼                                ▼
     OTel Collector (Docker, :4318 / :4317) ──▶ telemetry/*.jsonl
```

- On startup the API **creates the DynamoDB table** (with two indexes) and
  **loads 8 sample listings** from `test/data/listings.json`.
- Two API endpoints: `GET /listings/search` and `GET /listings/{id}`.
- Every click is **traced** with OpenTelemetry from the browser through the
  API to DynamoDB. An **OpenTelemetry Collector** (its config is in
  [`otel-collector/`](otel-collector/README.md)) writes the traces to files in
  `telemetry/` (see step 8).

Listings with status DRAFT or SOLD never show up in search. The data includes
both on purpose, so you can see how that works (see "What you'll learn").

---

## How we know it works

**117 automated tests, all passing**, plus a coverage gate. The functional
tests run against a **real AWS Emulator** with no mocks: the app starts,
creates its DynamoDB table, loads the data, and is called over HTTP.

| Suite | Tool | Runs against | Tests | Result |
|---|---|---|---:|---|
| Backend unit | JUnit 5, Mockito, AssertJ, OTel SDK testing | nothing: AWS calls are mocked at the SDK client | 63 | ✅ all pass |
| Backend functional | REST Assured on the running app, **with the OTel agent attached** | **AWS Emulator** (real DynamoDB API) | 12 | ✅ all pass |
| Frontend unit | Vitest, React Testing Library, MSW | mocked network (MSW intercepts `fetch`) | 42 | ✅ all pass |
| Frontend coverage gate | Vitest v8 coverage, gate at **90%** | — | — | ✅ 100% lines · 97% branches |
| Frontend build | TypeScript strict + Vite production build | — | — | ✅ builds cleanly |

<sub>Last full run: 2026-11-11, macOS (Apple silicon), Java 21, Node 24, AWS Emulator 1.0.322. Reproduce with `make test-unit` and `make test-functional`.</sub>

---

## Before you start

| You need | Check with | Notes |
|---|---|---|
| Docker Desktop | `docker ps` | must be running |
| Java 21 | `java -version` | e.g. `brew install openjdk@21`. Homebrew's keg-only install is found automatically |
| Maven 3.9+ | `mvn -v` | |
| Node.js 22.12+ | `node -v` | tested with Node 24 |
| AWS CLI v2 | `aws --version` | optional, only for exploring the emulator yourself (step 7) |

> **You never need real AWS credentials.** The emulator creates its own
> keys, and the scripts read them for you.
>
> **No separate collector project is needed.** The telemetry collector's
> image is built from this repo's `otel-collector/` folder automatically
> (step 4).

The emulator steps below follow the official guide, adapted to this project.
Links are in [References](#references). These instructions are for macOS
and Linux; the guide also covers Windows PowerShell.

---

## Run it, step by step

### Step 1: Build the AWS Emulator image (first time only)

AWS doesn't publish a prebuilt image; you build one from a short Dockerfile
that installs the emulator's Linux package ([Docker guide][docker]).

In an **empty folder**, create a file named `Dockerfile`:

```dockerfile
FROM public.ecr.aws/amazonlinux/amazonlinux:2023-minimal
ADD https://downloads.aws.com/aws-emulator/packages/aws-emulator/LTS/latest/aws-emulator-x86_64.rpm /tmp/aws-emulator.rpm
RUN rpm -i /tmp/aws-emulator.rpm && rm /tmp/aws-emulator.rpm \
    && mkdir -p /home/emulator && chown 1000 /home/emulator
USER 1000
ENV HOME=/home/emulator
EXPOSE 9297
ENTRYPOINT ["aws-emulator", "-b", "0.0.0.0"]
```

> **Apple silicon (M1/M2/M3…) or other Arm machines:** in the `ADD` line,
> replace `aws-emulator-x86_64.rpm` with `aws-emulator-aarch64.rpm`.
> Check with `uname -m`: `arm64`/`aarch64` means Arm.

Then build it:

```bash
docker build -t aws-emulator .
```

`-b 0.0.0.0` in the `ENTRYPOINT` makes the emulator listen on all addresses
*inside* the container, so Docker can forward its port. The run command in
step 3 still opens the port only to your own machine.

> Our scripts look for an image named `aws-emulator`. If you tag yours
> differently, set `EMULATOR_IMAGE=<name:tag>` when running `make`.

### Step 2: Install the frontend packages (first time only)

```bash
cd frontend && npm install && cd ..
```

### Step 3: Start the emulator

**Option A (simplest): skip this step.** `make run` (step 5) starts an
emulator for you, named `homehub-emulator`, if none is running.

**Option B: start it yourself**, as in the [Docker guide][docker]. Run this in
its **own terminal** (from any folder) and leave it running:

```bash
mkdir -p ~/.aws/emulator     # create the shared folder first (on Linux, Docker would make it root-owned)

PORT=9297
docker run --rm --memory=2g --memory-swap=2g -p 127.0.0.1:$PORT:$PORT \
  -e HOME="$HOME" -v "$HOME/.aws/emulator:$HOME/.aws/emulator" \
  aws-emulator -p $PORT --telemetry off
```

What each part does:

| Part | Why |
|---|---|
| `-p 127.0.0.1:$PORT:$PORT` | Reachable **only from your machine**. The short form `-p 9297:9297` would open it to your whole network. Use the **same port on both sides**: the emulator writes its port into the files it creates. |
| `-e HOME="$HOME" -v "$HOME/.aws/emulator:…"` | On every start the emulator writes **new credentials and a new HTTPS certificate** into `~/.aws/emulator/<port>/`. The mount puts those files on your computer, where the AWS CLI, the SDKs and our scripts read them. **Without it, nothing can connect.** `-e HOME` makes the paths inside the container match yours. |
| `--memory=2g --memory-swap=2g` | Caps memory at 2 GiB. If the emulator goes over, it stops (exit code 137) and you lose its data. |
| `--rm` | Docker removes the container when it stops. |
| `--telemetry off` | Don't send usage statistics to AWS. It's on by default; see [Telemetry][telemetry] for what's collected. |

On Linux, also add `--user "$(id -u):$(id -g)"` right after `docker run`, so
the shared files belong to you.

In another terminal, check that it's healthy:

```bash
curl --cacert ~/.aws/emulator/9297/ca.pem https://127.0.0.1:9297/__admin/health   # prints: ok
```

**Which emulator do our scripts use?** `scripts/emulator-env.sh` picks it like this:

1. If you set `EMULATOR_CONTAINER=<name>`, that container.
2. Otherwise, the **one** running container built from the `aws-emulator`
   image, whatever its name or port (e.g. the one from Option B).
3. If several are running, it stops and asks you to choose with
   `EMULATOR_CONTAINER`.
4. If none is running, it starts its own (Option A), with the same flags as
   above.

### Step 4: Start the telemetry collector

The collector receives traces, logs and metrics from the API and the browser,
and writes them to `telemetry/`. Details: [`otel-collector/README.md`](otel-collector/README.md).

**Option A (simplest): skip this step.** `make run` (step 5) does all of this
for you:

1. Builds the image `homehub-otel:local` from `otel-collector/`. It checks the
   config first, and rebuilds whenever you edit that folder.
2. Creates `telemetry/traces`, `telemetry/metrics` and `telemetry/logs`.
3. Starts the `homehub-otel` container (or replaces it if it runs an older
   image), then waits until it's healthy.

To do just this part, without the app: `make collector`.

**Option B: do it by hand** (to learn what's happening). Run from the
project root, so `$PWD/telemetry` is this project's folder:

```bash
# 1. Build the image (the first build downloads the collector base image)
docker build -t homehub-otel:local otel-collector/

# 2. Create the output folders (the image has no shell, so it can't create them)
mkdir -p telemetry/traces telemetry/metrics telemetry/logs

# 3. Start the collector, writing into THIS project's telemetry/ folder
docker run -d --name homehub-otel \
  -p 127.0.0.1:4317:4317 -p 127.0.0.1:4318:4318 -p 127.0.0.1:13133:13133 \
  -v "$PWD/telemetry:/telemetry" \
  homehub-otel:local

# 4. Check it's healthy
curl -s localhost:13133           # {"status":"Server available",...}
```

| Part | Why |
|---|---|
| `--name homehub-otel` | A fixed name; the scripts and `docker logs homehub-otel` use it |
| `-p 127.0.0.1:4317:4317` | The API sends telemetry here (gRPC) |
| `-p 127.0.0.1:4318:4318` | The browser sends traces here (HTTP) |
| `-p 127.0.0.1:13133:13133` | Health check |
| `-v "$PWD/telemetry:/telemetry"` | The collector writes to `/telemetry` inside the container; this makes that your `telemetry/` folder |

> Only one collector can use these ports at a time. If one is already running
> from another folder, the scripts stop with an error instead of sending your
> telemetry somewhere else. Stop it first with `docker stop homehub-otel`.

Watch it receive data live with `make collector-logs` (one line per batch).

### Step 5: Start the API

```bash
make run
```

The first run downloads two things, so give it a minute:

- the Maven dependencies (into `~/.m2`, as for any Maven project);
- the **OpenTelemetry Java agent**, a ~26 MB jar saved as
  `otel/opentelemetry-javaagent.jar`. It attaches to the app with
  `-javaagent:` and records every HTTP request and AWS call **without any
  tracing code in the app**. `otel/` is in `.gitignore`, so every clone
  downloads its own copy once. The script checks it against Maven Central's
  SHA-512 checksum, and downloads it again only when the version changes.
  The version is set in one place: `<otel.agent.version>` in
  `backend/pom.xml`.

You don't need to do anything for either. You're ready when you see lines like:

```
emulator-env: ready — <container> at https://127.0.0.1:9297 region=us-west-2 (credentials: ~/.aws/emulator/9297/emulator_credentials)
... Created DynamoDB table Listings with GSIs city-price-index, agent-index
... Seeded 8 listings from .../test/data
```

Leave this terminal running.

### Step 6: Start the web page (in another terminal)

```bash
make frontend-dev
```

Open **http://localhost:5173**. Try these:

- Search **Seattle**: you get 3 results, cheapest first.
- Set **Min price** `600000` and **Max price** `900000`: you get 2 results.
- Search **Portland**, then click a price to open the listing.

Other useful pages:

- Swagger UI (try the API in the browser): http://localhost:8080/swagger-ui.html
- Readiness: http://localhost:8080/actuator/health/readiness

### Step 7: Look inside the emulator with the AWS CLI

This step teaches the most. Load the emulator's connection settings into your
shell:

```bash
source scripts/emulator-env.sh
```

It sets the variables from the guide's ["To point your SDK at a local
emulator"][sdk-env] section, filled in for whichever emulator it found:

| Variable | What it does |
|---|---|
| `AWS_ENDPOINT_URL=https://127.0.0.1:9297` | Sends every request to the emulator instead of AWS |
| `AWS_SHARED_CREDENTIALS_FILE` / `AWS_CONFIG_FILE` | Use the emulator's keys and config (`~/.aws/emulator/9297/…`), not your own `~/.aws` files |
| `AWS_CA_BUNDLE` | Trust the emulator's HTTPS certificate (`ca.pem`) |
| `AWS_EC2_METADATA_DISABLED=true` | Never pick up real credentials from an EC2 instance |
| `AWS_DISABLE_HOST_PREFIX_INJECTION=true` | Stops the CLI from changing `127.0.0.1` into hosts like `data-127.0.0.1` for some operations |
| `AWS_REGION=us-west-2` | The region this project uses. The guide's example uses `us-east-1`, but each region is a separate world, so pick one and stick to it |

It also **unsets** `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`,
`AWS_SESSION_TOKEN` and `AWS_PROFILE`. Real keys in env vars would win over
the emulator's credentials file.

> ⚠️ **Important:** in a shell where you haven't run this, `aws` commands go to
> **real AWS**. Always check first ([guide][sdk-verify]):
>
> ```bash
> aws sts get-caller-identity
> # On the emulator: "Account": "000000000000", "Arn": "arn:aws:iam::000000000000:user/emulator"
> # If you see your own account ID, STOP: you're talking to real AWS.
> ```

Now explore:

```bash
# Which tables exist?
aws dynamodb list-tables

# How many items are in the table?
aws dynamodb scan --table-name Listings --select COUNT --query Count        # 8

# What indexes does the table have?
aws dynamodb describe-table --table-name Listings \
  --query 'Table.GlobalSecondaryIndexes[].{name:IndexName,keys:KeySchema[].AttributeName}' --output table

# The same query the search page runs: Seattle, $600k–$900k, via the index
aws dynamodb query --table-name Listings --index-name city-price-index \
  --key-condition-expression 'searchCity = :c AND price BETWEEN :lo AND :hi' \
  --expression-attribute-values '{":c":{"S":"seattle"},":lo":{"N":"600000"},":hi":{"N":"900000"}}' \
  --query 'Items[].[listingId.S,price.N,status.S]' --output text

# Why a DRAFT listing never appears in search: it has no "searchCity" value
aws dynamodb get-item --table-name Listings --key '{"listingId":{"S":"LST-0004"}}' \
  --query 'Item.{status:status.S,searchCity:searchCity.S}'
```

You can also call the API directly:

```bash
curl 'http://localhost:8080/listings/search?city=Seattle&minBeds=3'
curl  http://localhost:8080/listings/LST-0001
curl  http://localhost:8080/listings/LST-0004      # DRAFT → 404
curl 'http://localhost:8080/listings/search?city=Seattle&limit=2'   # note "nextToken" for page 2
```

### Step 8: See what happened (traces and logs)

Click around the web page, then run:

```bash
make telemetry
```

You'll see one trace per user action, for example:

```
search-listings              [homehub-web]   ← your click in the browser
  GET                        [homehub-web]   ← the fetch call
    GET /listings/search     [homehub-api]   ← the API request (same trace!)
      DynamoDb.Query         [homehub-api]   ← the call to the emulator
```

The raw files are in `telemetry/traces/`, `telemetry/logs/` and
`telemetry/metrics/`. Run `make telemetry-clean` to empty them.

### Step 9: Run the tests

The suites and their latest results are in [How we know it works](#how-we-know-it-works).

```bash
make test-unit          # fast, no emulator needed
make test-functional    # starts the full app against the emulator (and the collector)
```

### Step 10: Stop everything

- Press `Ctrl+C` in the `make run` and `make frontend-dev` terminals.
- **Option B emulator:** press `Ctrl+C` in its terminal. It deletes its files
  in `~/.aws/emulator/<port>/`, and `--rm` removes the container.
- **Option A emulator:** `make down`. It only ever stops an emulator **the
  scripts started**, never one you started yourself.

- **Collector:** leave it running (it's cheap), or `docker stop homehub-otel`.
  Files already in `telemetry/` stay.

Either way, **all emulator data is gone**: it lives only in memory. Next
`make run`, the app creates the table and loads the data again.

---

## What you'll learn about the AWS Emulator

| Fact | What it means for your code |
|---|---|
| **HTTPS only**, with a certificate it creates on every start (`~/.aws/emulator/<port>/ca.pem`) | Tools must trust that file. The CLI uses `AWS_CA_BUNDLE`. For Java, see the note below. |
| **New access keys on every start** (`~/.aws/emulator/<port>/emulator_credentials`; IDs currently start with `EMULATOR`) | Never hardcode keys; point the SDK at the file. Don't check the key format in code either: the guide says it can change in any release. Requests signed with other keys get HTTP 403. |
| **One account**: `000000000000` | `aws sts get-caller-identity` tells you instantly whether you're on the emulator. |
| **Everything is in memory**: stopping the emulator deletes all tables | That's why the app creates its table and data **on every startup**, skipping whatever already exists. |
| **Regions are separate**: a table in `us-west-2` is invisible in `us-east-1` | Always set the region, and use the same one everywhere. |
| **The endpoint is the only difference from real AWS** | The app reads `AWS_ENDPOINT_URL`. Leave it unset (the `aws` Spring profile) and the same code talks to real AWS. |
| **No IAM checks**: requests real AWS would deny can succeed here | Don't use the emulator to test permissions or security. |
| **Unimplemented operations return HTTP 501 `NotImplemented`** ("operation X is scaffolded but not implemented") | Try it: `aws gamelift list-fleets`. The startup code catches this and names the operation instead of failing with a vague error. |

> **Java note.** The guide's Java approach sets `JAVA_TOOL_OPTIONS` to the
> emulator's `truststore.p12`. That *replaces* the JVM's trusted certificates,
> so calls to any other HTTPS server fail with `PKIX path building failed`
> ([guide][sdk-ca-both]). This project trusts `ca.pem` **only inside the AWS
> SDK client** instead (`backend/src/main/java/com/homehub/config/PemTrustManagers.java`),
> so the rest of the app is unaffected.

And about DynamoDB (look at `DynamoDbListingRepository.java`):

- **Sparse index:** only listings that can be searched get a `searchCity`
  value. DynamoDB copies an item into the index only if it has the index's
  keys, so DRAFT and SOLD listings are never in the index, and search can't
  return them.
- **Key condition vs. filter:** the price range is part of the index key, so
  DynamoDB reads only the matching items. "Min beds" is a *filter*, applied
  after reading. That's why a page can come back with fewer items than
  `limit`.
- **Pagination:** DynamoDB returns a `LastEvaluatedKey`, which our API turns
  into an opaque `nextToken` for the next page.

---

## Troubleshooting

| Message / symptom | Fix |
|---|---|
| `several emulator containers are running — choose one` | Pick one: `EMULATOR_CONTAINER=<name> make run` |
| `… doesn't mount its credentials folder to the host` | Restart that emulator with the `-e HOME` and `-v` flags from step 3 |
| `… maps host port X to container port Y` | Use the same port on both sides of `-p`. Otherwise two emulators can overwrite each other's files ([Docker guide][docker]) |
| `image aws-emulator:latest not found locally` | Do step 1, or set `EMULATOR_IMAGE=<your image name>` |
| `otel-collector/otel-collector.yaml is invalid` | The error above it names the problem. Fix the YAML; a running collector is left alone |
| `port 4317 is already in use` / `homehub-otel … writes to '…'` | Another collector is running: `docker stop homehub-otel`, then `make collector` |
| `Profile 'local' requires AWS_ENDPOINT_URL` | Start the app with `make run` (it sets everything up), not `mvn spring-boot:run` directly |
| `note — usage telemetry is ON in <container>` | Just information: that emulator was started without `--telemetry off` ([how to turn it off][telemetry-off]) |
| HTTP 403 (`AccessDenied` "Sign the request with the emulator credentials", or `… security token … invalid`) | You're using old or real keys. The emulator makes new keys each start: run `source scripts/emulator-env.sh` again |
| Emulator container exits with code 137 | It ran out of its 2 GiB memory. Restart it; the data is gone |
| Port 8080 or 5173 already in use | Stop the other process: `lsof -ti tcp:8080 \| xargs kill` |
| `could not download the OTel Java agent from Maven Central` | Usually a proxy or firewall. Set `HTTPS_PROXY` and retry, or download the jar yourself with the `curl -L -o otel/opentelemetry-javaagent.jar …` command the message prints (or save that URL in a browser as `otel/opentelemetry-javaagent.jar`), then run `make run` again. The script accepts a jar you placed there as long as its version matches |
| `checksum mismatch for the downloaded agent jar` | The download was corrupted, or a proxy changed it. Delete `otel/opentelemetry-javaagent.jar` and retry |
| `telemetry/` files stay empty | Check the collector: `curl localhost:13133` and `make collector-logs`. More in [`otel-collector/README.md`](otel-collector/README.md#troubleshooting) |

## References

AWS Emulator User Guide:

- [Getting started with Docker][docker]: build the image, run the container, ports, memory, removing it
- [Pointing your AWS SDK at AWS Emulator][sdk]: the environment variables, the `get-caller-identity` check, trusting the certificate in each SDK
- [Telemetry in AWS Emulator][telemetry]: what's collected and [how to turn it off][telemetry-off]
- [Command reference][cli]: all options, local files, credentials and error codes
- [AWS Emulator repository](https://github.com/aws/aws-emulator)

This project:

- [`otel-collector/README.md`](otel-collector/README.md): the telemetry
  collector, how its pipeline works, and its contract with the app.
- `CLAUDE.md`: project rules and conventions.
- `specs/`: requirements, design and decisions for the full app, which grows
  beyond this slice phase by phase.

[docker]: https://docs.aws.amazon.com/emulator/latest/userguide/getting-started-docker.html
[sdk]: https://docs.aws.amazon.com/emulator/latest/userguide/point-sdk-to-emulator.html
[sdk-env]: https://docs.aws.amazon.com/emulator/latest/userguide/point-sdk-to-emulator.html#point-sdk-env
[sdk-verify]: https://docs.aws.amazon.com/emulator/latest/userguide/point-sdk-to-emulator.html#point-sdk-verify
[sdk-ca-both]: https://docs.aws.amazon.com/emulator/latest/userguide/point-sdk-to-emulator.html#point-sdk-ca-both
[telemetry]: https://docs.aws.amazon.com/emulator/latest/userguide/telemetry.html
[telemetry-off]: https://docs.aws.amazon.com/emulator/latest/userguide/telemetry.html#telemetry-opt-out
[cli]: https://docs.aws.amazon.com/emulator/latest/userguide/cli-reference.html
