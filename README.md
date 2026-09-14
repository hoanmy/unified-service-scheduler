# Unified Service Scheduler

> **Keyloop Automotive Retail Platform — Scenario A: Ownership Domain**  
> Enterprise-grade reactive backend for service appointment reservations and real-time bay/technician availability.

**Architecture:** CQRS · Clean Architecture · Reactive Streams · Transactional Outbox · Event-Driven Architecture  
**Stack:** Java 21 · Quarkus 3.15.1 · PostgreSQL 16 · Redis 7 · Apache Kafka · Elasticsearch 8 · Debezium CDC

---

## Table of Contents

1. [Prerequisites](#1-prerequisites)
2. [Project Structure](#2-project-structure)
3. [Build the Application](#3-build-the-application)
4. [Run Locally](#4-run-locally)
5. [Run Tests](#5-run-tests)
6. [Explore the API](#6-explore-the-api)
7. [Functional Requirements Coverage](#7-functional-requirements-coverage)
8. [Architecture Overview](#8-architecture-overview)
9. [Troubleshooting](#9-troubleshooting)

---

## 1. Prerequisites

Ensure the following tools are installed before proceeding:

| Tool | Required Version | Install Guide |
|---|---|---|
| **Java (JDK)** | 21 (LTS) | [Adoptium](https://adoptium.net/) or `sdk install java 21-tem` |
| **Maven** | 3.9+ (or use `./mvnw`) | Bundled via Maven Wrapper — no install needed |
| **Docker** | 24+ | [Docker Desktop](https://www.docker.com/products/docker-desktop/) |
| **Docker Compose** | v2 (`docker compose`) | Included with Docker Desktop |

### Verify your environment

```bash
java -version
# openjdk version "21.0.x" ...

./mvnw -version
# Apache Maven 3.9.x

docker --version
# Docker version 24.x.x

docker compose version
# Docker Compose version v2.x.x
```

> **Windows users:** Use Git Bash, WSL2, or PowerShell. All commands below work in Git Bash/WSL2.  
> Replace `./mvnw` with `mvnw.cmd` if using PowerShell/CMD.

---

## 2. Project Structure

```
unified-service-scheduler/
├── pom.xml                         ← Maven build (Quarkus 3.15.1 BOM)
├── docker-compose.dev.yml          ← Local infrastructure
├── README.md                       ← This file
│
├── docs/
│   ├── api-contract.md             ← Full API specification with examples
│   └── scheduler.http              ← VS Code REST Client test harness
│
├── scripts/
│   ├── curl-examples.sh            ← Bash cURL test harness
│   ├── debezium-connector.json     ← CDC connector configuration
│   └── register-debezium.sh       ← Registers CDC connector (one-time)
│
└── src/
    ├── main/
    │   ├── java/com/keyloop/scheduler/
    │   │   ├── domain/             ← Layer 1: Pure Java entities & value objects
    │   │   │   ├── entity/         ← Appointment (aggregate root), Technician, OutboxEvent
    │   │   │   ├── valueobject/    ← TimeSlot [start,end), AppointmentStatus, EventType
    │   │   │   └── exception/      ← Typed domain exceptions
    │   │   │
    │   │   ├── application/        ← Layer 2: Use cases & port interfaces
    │   │   │   ├── port/in/        ← Input ports (CheckAvailability, BookAppointment, Cancel)
    │   │   │   ├── port/out/       ← Output ports (Repository, Lock, Idempotency, Search)
    │   │   │   └── usecase/        ← Business logic implementations
    │   │   │
    │   │   └── infrastructure/     ← Layer 3: Framework adapters
    │   │       ├── persistence/    ← Hibernate Reactive Panache + Flyway migrations
    │   │       ├── cache/          ← Redis distributed lock + idempotency adapters
    │   │       ├── messaging/      ← Kafka consumers (SearchSyncWorker, NotificationWorker)
    │   │       ├── search/         ← Elasticsearch availability adapter
    │   │       └── web/            ← JAX-RS REST resources + DTOs + exception mapper
    │   │
    │   └── resources/
    │       ├── application.properties      ← Main configuration
    │       ├── application-dev.properties  ← Dev overrides (SQL logging, Swagger on)
    │       └── db/migration/
    │           ├── V1__init_schema.sql     ← Schema + GiST exclusion constraints
    │           └── V2__seed_data.sql       ← Test data (dealerships, technicians, bays)
    │
    └── test/
        ├── application/usecase/
        │   └── BookAppointmentUseCaseTest.java  ← Unit tests (5-phase flow)
        ├── infrastructure/web/
        │   └── AppointmentResourceIT.java        ← Integration tests (concurrency)
        └── CleanArchitectureTest.java             ← ArchUnit dependency rules
```

---

## 3. Build the Application

### Option A — Compile only (verify the code compiles)

```bash
# Using Maven Wrapper (recommended — no Maven install needed)
./mvnw compile

# Or on Windows PowerShell/CMD:
mvnw.cmd compile
```

### Option B — Full build with unit tests

```bash
./mvnw package -DskipITs
```

Output: `target/unified-service-scheduler-1.0.0-SNAPSHOT-runner.jar`

### Option C — Build without running any tests

```bash
./mvnw package -DskipTests
```

### Option D — Native executable (GraalVM required)

```bash
# Build inside a Docker container (no local GraalVM needed)
./mvnw package -Dnative -Dquarkus.native.container-build=true

# Output: target/unified-service-scheduler-1.0.0-SNAPSHOT-runner (Linux binary)
```

> **Tip:** Native compilation takes 3–10 minutes. Use JVM mode (`./mvnw quarkus:dev`) for development.

---

## 4. Run Locally

### Step 1 — Start the infrastructure

```bash
docker compose -f docker-compose.dev.yml up -d
```

Wait ~30 seconds for all services to become healthy. Check status:

```bash
docker compose -f docker-compose.dev.yml ps
```

All services should show `healthy` or `running`:

| Service | URL | Credentials |
|---|---|---|
| **PostgreSQL 16** | `localhost:5432` | `scheduler_user` / `scheduler_pass` / DB: `scheduler_db` |
| **Redis 7** | `localhost:6379` | No auth |
| **Kafka** | `localhost:9092` | No auth |
| **Kafka UI** | http://localhost:8090 | No auth |
| **Elasticsearch 8** | http://localhost:9200 | No auth (security disabled for dev) |
| **Kibana** | http://localhost:5601 | No auth |
| **Debezium Connect** | http://localhost:8083 | REST API |
| **Debezium UI** | http://localhost:8085 | No auth |

### Step 2 — Start the application in dev mode

```bash
./mvnw quarkus:dev
```

**What happens automatically:**
- Flyway runs `V1__init_schema.sql` (creates all tables + GiST constraints)
- Flyway runs `V2__seed_data.sql` (populates test data)
- Quarkus starts with live reload enabled

Application is ready when you see:
```
__  ____  __  _____   ___  __ ____  ______
 --/ __ \/ / / / _ | / _ \/ //_/ / / / __/
 -/ /_/ / /_/ / __ |/ , _/ ,< / /_/ /\ \
--\___\_\____/_/ |_/_/|_/_/|_|\____/___/
...
INFO  Listening on: http://0.0.0.0:8080
```

### Step 3 — Register the Debezium CDC connector (one-time)

This enables the PostgreSQL WAL → Kafka event pipeline:

```bash
# On Mac/Linux:
chmod +x scripts/register-debezium.sh && ./scripts/register-debezium.sh

# On Windows (Git Bash/WSL2):
bash scripts/register-debezium.sh

# Or manually via curl:
curl -X POST http://localhost:8083/connectors \
  -H "Content-Type: application/json" \
  -d @scripts/debezium-connector.json
```

Verify the connector is running:
```bash
curl -s http://localhost:8083/connectors/outbox-event-connector/status | python3 -m json.tool
```

Expected: `"state": "RUNNING"`

### Step 4 — Verify everything works

```bash
# Health check
curl http://localhost:8080/q/health/ready

# Expected:
# {"status":"UP","checks":[...]}
```

### Stopping the application

```bash
# Stop Quarkus (Ctrl+C in the terminal running ./mvnw quarkus:dev)

# Stop all Docker containers
docker compose -f docker-compose.dev.yml down

# Stop AND remove volumes (full reset of DB, Redis, Kafka data)
docker compose -f docker-compose.dev.yml down -v
```

---

## 5. Run Tests

### Unit Tests (no Docker required)

Tests the 5-phase booking flow, idempotency, and concurrency scenarios using **mocked ports** — no real database or Redis needed.

```bash
./mvnw test
```

**What runs:**
- `BookAppointmentUseCaseTest` — 5-phase booking flow (happy path + all failure scenarios)
- `CleanArchitectureTest` — ArchUnit rules verifying Clean Architecture dependency constraints

Expected output:
```
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

### Integration Tests (requires Docker)

Full end-to-end tests using **Testcontainers** — spins up real PostgreSQL, Redis, and Kafka containers.

```bash
# Make sure Docker is running, then:
./mvnw verify -Pintegration-tests
```

**What runs:**
- `AppointmentResourceIT` — REST API tests including:
  - FR-01: Availability check with Cache-Control header
  - FR-02: Full booking flow (201 Created)
  - FR-03: Idempotency (same key → same response)
  - **NFR: Zero double-booking** — 10 concurrent threads, verifies ≤ 1 succeeds
  - FR-04: Missing Idempotency-Key → 400

Expected output:
```
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

### Run a specific test class

```bash
# Run only the use case unit test
./mvnw test -Dtest=BookAppointmentUseCaseTest

# Run only architecture rules
./mvnw test -Dtest=CleanArchitectureTest

# Run only integration tests
./mvnw verify -Pintegration-tests -Dit.test=AppointmentResourceIT
```

### Test with verbose output

```bash
./mvnw test -Dsurefire.failIfNoSpecifiedTests=false -pl . -Dquarkus.log.level=DEBUG
```

---

## 6. Explore the API

### Interactive Swagger UI

Open in your browser after starting the app:

```
http://localhost:8080/q/swagger-ui
```

Allows you to try all endpoints directly with a visual interface.

### Raw OpenAPI Spec

```bash
# YAML format
curl http://localhost:8080/q/openapi

# JSON format
curl -H "Accept: application/json" http://localhost:8080/q/openapi
```

### VS Code REST Client (recommended)

1. Install the [REST Client extension](https://marketplace.visualstudio.com/items?itemName=humao.rest-client)
2. Open [`docs/scheduler.http`](docs/scheduler.http)
3. Click **"Send Request"** above any `###` block

The file contains complete scenarios for all FRs with pre-filled seed data UUIDs.

### cURL Test Harness (automated)

```bash
# Make sure the app is running first (./mvnw quarkus:dev)
chmod +x scripts/curl-examples.sh
./scripts/curl-examples.sh
```

Runs all FR scenarios with colored output and reports pass/fail.

### Quick API Reference

#### FR-01 — Check Availability

```bash
curl -s "http://localhost:8080/api/v1/dealerships/11111111-0000-0000-0000-000000000001/availability?\
service_type_id=22222222-0000-0000-0000-000000000001&\
start_date=2026-10-01&end_date=2026-10-07" | python3 -m json.tool
```

#### FR-02 — Book Appointment

```bash
curl -s -X POST http://localhost:8080/api/v1/appointments \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(uuidgen || python3 -c 'import uuid; print(uuid.uuid4())')" \
  -d '{
    "customerId":    "55555555-0000-0000-0000-000000000001",
    "vehicleId":     "66666666-0000-0000-0000-000000000001",
    "dealershipId":  "11111111-0000-0000-0000-000000000001",
    "serviceTypeId": "22222222-0000-0000-0000-000000000001",
    "technicianId":  "33333333-0000-0000-0000-000000000001",
    "serviceBayId":  "44444444-0000-0000-0000-000000000001",
    "startTime":     "2026-10-01T09:00:00Z"
  }' | python3 -m json.tool
```

#### FR-04 — Cancel Appointment

```bash
# Replace {appointmentId} with the UUID from the booking response
curl -s -X PATCH http://localhost:8080/api/v1/appointments/{appointmentId}/status \
  -H "Content-Type: application/json" \
  -d '{"status": "CANCELLED", "reason": "Customer requested cancellation"}' \
  | python3 -m json.tool
```

#### Health & Metrics

```bash
curl http://localhost:8080/q/health          # Full health
curl http://localhost:8080/q/health/live     # Liveness
curl http://localhost:8080/q/health/ready    # Readiness
curl http://localhost:8080/q/metrics         # Prometheus metrics
```

---

## 7. Functional Requirements Coverage

| FR | Requirement | Status | Implementation |
|---|---|---|---|
| **FR-01** | Availability Search | ✅ | `AvailabilityResource` → `CheckAvailabilityUseCaseImpl` → Elasticsearch |
| **FR-02** | Constrained Booking | ✅ | `AppointmentResource` → `BookAppointmentUseCaseImpl` (5-phase flow) |
| **FR-03** | Idempotency (24h) | ✅ | `RedisIdempotencyAdapter` + `Idempotency-Key` header validation |
| **FR-04** | Cancellation & Release | ✅ | `CancelAppointmentUseCaseImpl` + outbox event → ES sync < 200ms |
| **FR-05** | Multi-Channel Alerts | ✅ | `NotificationWorker` (Kafka Consumer Group B) |

| NFR | Target SLO | Implementation |
|---|---|---|
| Read latency p95 < 200ms | 100k RPS | Elasticsearch CQRS + `Cache-Control: public, max-age=15` |
| Zero double-booking | 100% | Redis SETNX (Layer 1) + PostgreSQL GiST exclusion (Layer 2) |
| Notification delivery 99.99% | < 2s async | Kafka consumer with retry strategy (5 attempts, 1s delay) |
| Slot sync < 200ms | Eventual | Debezium WAL → Kafka → `SearchSyncWorker` bulk ES update |

---

## 8. Architecture Overview

### Clean Architecture Layers

```
┌────────────────────────────────────────────────────────────┐
│  INFRASTRUCTURE (Layer 3 — outermost)                      │
│  ┌─ persistence/ ─┐  ┌─ cache/ ─┐  ┌─ web/ ─┐            │
│  │ Panache/Flyway │  │  Redis   │  │ JAX-RS │            │
│  └────────────────┘  └──────────┘  └────────┘            │
│         ↓ implements ports ↓                               │
│  ┌────────────────────────────────────────────────────┐   │
│  │  APPLICATION (Layer 2)                             │   │
│  │  port/in:  CheckAvailability, BookAppointment,     │   │
│  │            CancelAppointment                       │   │
│  │  port/out: AppointmentRepository, DistributedLock, │   │
│  │            IdempotencyPort, AvailabilitySearch     │   │
│  │  usecase:  BookAppointmentUseCaseImpl (5-phase)    │   │
│  └───────────────────────┬────────────────────────────┘   │
│                          ↓ depends on ↓                    │
│  ┌────────────────────────────────────────────────────┐   │
│  │  DOMAIN (Layer 1 — innermost, no framework deps)   │   │
│  │  entity:      Appointment, Technician, OutboxEvent │   │
│  │  valueobject: TimeSlot, AppointmentStatus          │   │
│  │  exception:   DoubleBookingException, ...          │   │
│  └────────────────────────────────────────────────────┘   │
└────────────────────────────────────────────────────────────┘
```

### 5-Phase Booking Flow (Zero Double-Booking)

```
POST /api/v1/appointments  (Idempotency-Key: <uuid>)
│
├─ Phase 1: Redis GET idempotency:{key}
│           → HIT  → return cached 201 in < 5ms  ✓
│           → MISS → continue
│
├─ Phase 2: Redis SET lock:tech:{id}:{slot} NX EX 5s
│           → FAIL → 409 Conflict in < 5ms  ✓ (99.99% of contention)
│           → OK   → continue
│
├─ Phase 3: PostgreSQL BEGIN TRANSACTION
│           → INSERT appointment (time_slot TSTZRANGE)
│           → INSERT outbox_event (same transaction)
│           → COMMIT  (GiST exclusion = absolute failsafe)
│           → ROLLBACK on constraint → 409 Conflict  ✓
│
├─ Phase 4: Redis DEL lock:tech + lock:bay
│           Redis SET idempotency:{key} {response} EX 86400
│           → return 201 Created  ✓
│
└─ Phase 5: [ASYNC] Debezium reads WAL
            → Kafka topic: appointment-events
            → Consumer Group A: Elasticsearch update (< 200ms)
            → Consumer Group B: Email / SMS / FCM / APNs
```

### Infrastructure Component Map

| Component | Role | Technology |
|---|---|---|
| API Gateway | Rate limiting, routing, edge caching | Cloudflare + AWS ALB |
| Schedule Service | CQRS read path | Quarkus + Elasticsearch |
| Reservation Service | CQRS write path | Quarkus + PostgreSQL |
| Distributed Lock | Thundering herd deflection | Redis (SETNX NX EX) |
| Idempotency Cache | Duplicate request prevention | Redis (GET/SET EX 86400) |
| Transactional DB | ACID writes, Single Source of Truth | Amazon Aurora PostgreSQL Multi-AZ |
| CDC Connector | WAL extraction → Kafka (no polling) | Debezium on MSK Connect |
| Event Stream | Per-dealership ordered delivery | Amazon MSK (Kafka) |
| Search Index | Multi-tenant availability queries | Amazon OpenSearch Multi-AZ |
| Notification | Async multi-channel dispatch | ECS Workers + Circuit Breakers |

---

## 9. Troubleshooting

### Application won't start — "Connection refused to PostgreSQL"

```bash
# Check PostgreSQL container is running
docker compose -f docker-compose.dev.yml ps postgres

# View PostgreSQL logs
docker compose -f docker-compose.dev.yml logs postgres

# Restart if needed
docker compose -f docker-compose.dev.yml restart postgres
```

### Flyway migration fails — "relation already exists"

```bash
# Full reset of database (WARNING: deletes all data)
docker compose -f docker-compose.dev.yml down -v
docker compose -f docker-compose.dev.yml up -d
./mvnw quarkus:dev
```

### Redis connection error

```bash
# Test Redis connectivity
docker exec uss-redis redis-cli ping
# Expected: PONG

# Check Redis logs
docker compose -f docker-compose.dev.yml logs redis
```

### Debezium connector status is "FAILED"

```bash
# View detailed connector status
curl -s http://localhost:8083/connectors/outbox-event-connector/status | python3 -m json.tool

# Delete and re-register connector
curl -X DELETE http://localhost:8083/connectors/outbox-event-connector
./scripts/register-debezium.sh
```

### Elasticsearch returns 503

```bash
# Check cluster health
curl http://localhost:9200/_cluster/health?pretty

# View Elasticsearch logs
docker compose -f docker-compose.dev.yml logs elasticsearch
```

### `./mvnw: Permission denied` (Linux/Mac)

```bash
chmod +x mvnw
./mvnw quarkus:dev
```

### `mvnw.cmd` not found (Windows)

```powershell
# Use Maven directly if installed
mvn quarkus:dev

# Or ensure you are in the project root directory
cd "c:\Users\HoanM\OneDrive\Documents\Keyloop challenges\keyloop\unified-service-scheduler"
.\mvnw.cmd quarkus:dev
```

### Port already in use

```bash
# Check what is using port 8080
# Mac/Linux:
lsof -i :8080

# Windows:
netstat -aon | findstr :8080

# Change Quarkus port if needed
./mvnw quarkus:dev -Dquarkus.http.port=8081
```

---

## Additional Resources

| Resource | Path |
|---|---|
| Full API Contract | [`docs/api-contract.md`](docs/api-contract.md) |
| HTTP Test File (VS Code) | [`docs/scheduler.http`](docs/scheduler.http) |
| cURL Test Harness | [`scripts/curl-examples.sh`](scripts/curl-examples.sh) |
| DB Schema (Flyway) | [`src/main/resources/db/migration/V1__init_schema.sql`](src/main/resources/db/migration/V1__init_schema.sql) |
| Seed Data | [`src/main/resources/db/migration/V2__seed_data.sql`](src/main/resources/db/migration/V2__seed_data.sql) |
| App Configuration | [`src/main/resources/application.properties`](src/main/resources/application.properties) |
| System Design Doc | [`../new_sys_desgin_doc.md`](../new_sys_desgin_doc.md) |
| Architecture Guardrails | [`../CLAUDE.md`](../CLAUDE.md) |
