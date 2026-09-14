# API Contract — Unified Service Scheduler

**Base URL:** `http://localhost:8080`  
**Content-Type:** `application/json`  
**OpenAPI UI:** `http://localhost:8080/q/swagger-ui` (when running with `./mvnw quarkus:dev`)

---

## Authentication

> Not in scope for this exercise. In production: JWT via Keycloak/AWS Cognito passed as `Authorization: Bearer <token>`.

---

## FR-01 — Availability Check

### `GET /api/v1/dealerships/{dealershipId}/availability`

Returns available time slots for a given dealership, service type, and date range.  
Backed by Elasticsearch. Response is cached at CDN edge (`Cache-Control: public, max-age=15`).

**SLO:** p95 < 200ms, p99 < 500ms at 100,000 RPS.

#### Path Parameters

| Parameter | Type | Required | Description |
|---|---|---|---|
| `dealershipId` | UUID | ✅ | Dealership identifier |

#### Query Parameters

| Parameter | Type | Required | Example | Description |
|---|---|---|---|---|
| `service_type_id` | UUID | ✅ | `22222222-0000-0000-0000-000000000001` | Service catalog identifier |
| `start_date` | ISO-8601 Date | ✅ | `2026-10-01` | Search window start |
| `end_date` | ISO-8601 Date | ✅ | `2026-10-07` | Search window end |

#### Response Headers

```
Cache-Control: public, max-age=15
ETag: <hash>
Content-Type: application/json
```

#### `200 OK` — Available slots found

```json
{
  "dealership_id": "11111111-0000-0000-0000-000000000001",
  "service_type_id": "22222222-0000-0000-0000-000000000001",
  "duration_minutes": 45,
  "available_slots": [
    {
      "start_time": "2026-10-01T09:00:00Z",
      "end_time": "2026-10-01T09:45:00Z",
      "assigned_technician_id": "33333333-0000-0000-0000-000000000001",
      "assigned_service_bay_id": "44444444-0000-0000-0000-000000000001"
    },
    {
      "start_time": "2026-10-01T10:00:00Z",
      "end_time": "2026-10-01T10:45:00Z",
      "assigned_technician_id": "33333333-0000-0000-0000-000000000002",
      "assigned_service_bay_id": "44444444-0000-0000-0000-000000000002"
    }
  ]
}
```

#### `400 Bad Request` — Invalid parameters

```json
{
  "error": "Validation failed",
  "violations": [
    "startDate: must not be null",
    "endDate: must not be before startDate"
  ]
}
```

#### `503 Service Unavailable` — Elasticsearch circuit breaker open

```json
{
  "dealership_id": "11111111-0000-0000-0000-000000000001",
  "service_type_id": "22222222-0000-0000-0000-000000000001",
  "duration_minutes": 0,
  "available_slots": []
}
```

---

## FR-02 + FR-03 — Book Appointment (with Idempotency)

### `POST /api/v1/appointments`

Creates a confirmed appointment binding customer, vehicle, technician, and service bay.

**Idempotency:** The `Idempotency-Key` header (UUIDv4) guarantees that duplicate requests within a **24-hour window** return the same `201 Created` response without re-executing the booking flow.

**Concurrency:** The service acquires a Redis distributed lock before executing the ACID transaction. If the slot is contended, `409 Conflict` is returned in **< 5ms**.

#### Required Headers

| Header | Type | Required | Description |
|---|---|---|---|
| `Idempotency-Key` | UUIDv4 string | ✅ | Client-generated unique token. Store and reuse on retries. |
| `Content-Type` | `application/json` | ✅ | |

#### Request Body

```json
{
  "customerId":    "55555555-0000-0000-0000-000000000001",
  "vehicleId":     "66666666-0000-0000-0000-000000000001",
  "dealershipId":  "11111111-0000-0000-0000-000000000001",
  "serviceTypeId": "22222222-0000-0000-0000-000000000001",
  "technicianId":  "33333333-0000-0000-0000-000000000001",
  "serviceBayId":  "44444444-0000-0000-0000-000000000001",
  "startTime":     "2026-10-01T09:00:00Z"
}
```

#### Request Body Schema

| Field | Type | Required | Constraints |
|---|---|---|---|
| `customerId` | UUID | ✅ | Must exist in `customer` table |
| `vehicleId` | UUID | ✅ | Must belong to the customer |
| `dealershipId` | UUID | ✅ | Must be ACTIVE |
| `serviceTypeId` | UUID | ✅ | Must belong to the dealership |
| `technicianId` | UUID | ✅ | Skill level must meet service requirement |
| `serviceBayId` | UUID | ✅ | Must be OPERATIONAL |
| `startTime` | ISO-8601 DateTime | ✅ | Must be a future timestamp |

#### `201 Created` — Appointment confirmed

```
Location: /api/v1/appointments/aaa00000-1234-5678-abcd-000000000001
```

```json
{
  "appointmentId":  "aaa00000-1234-5678-abcd-000000000001",
  "status":         "CONFIRMED",
  "dealershipId":   "11111111-0000-0000-0000-000000000001",
  "customerId":     "55555555-0000-0000-0000-000000000001",
  "vehicleId":      "66666666-0000-0000-0000-000000000001",
  "serviceTypeId":  "22222222-0000-0000-0000-000000000001",
  "technicianId":   "33333333-0000-0000-0000-000000000001",
  "serviceBayId":   "44444444-0000-0000-0000-000000000001",
  "startTime":      "2026-10-01T09:00:00Z",
  "endTime":        "2026-10-01T09:45:00Z",
  "createdAt":      "2026-09-14T16:30:00Z"
}
```

#### `409 Conflict` — Resource already allocated (double-booking prevented)

```json
{
  "errorCode": "RESOURCE_ALREADY_ALLOCATED",
  "message": "The requested technician or service bay is no longer available for the specified time slot.",
  "suggestedSlotsUrl": "/api/v1/dealerships/11111111-0000-0000-0000-000000000001/availability?service_type_id=22222222-0000-0000-0000-000000000001&start_date=2026-10-01&end_date=2026-10-01"
}
```

#### `409 Conflict` — Redis lock contention (thundering herd deflected)

```json
{
  "errorCode": "RESOURCE_LOCKED",
  "message": "This slot is being processed by a concurrent request. Please refresh availability.",
  "suggestedSlotsUrl": null
}
```

#### `400 Bad Request` — Missing or invalid Idempotency-Key

```json
{
  "error": "Idempotency-Key must be a valid UUIDv4"
}
```

---

## FR-04 — Cancel Appointment

### `PATCH /api/v1/appointments/{appointmentId}/status`

Cancels a CONFIRMED appointment and releases the technician and bay resources.

After cancellation, the slot is restored in Elasticsearch within **< 200ms** via the CDC → Kafka → SearchSyncWorker pipeline.

#### Path Parameters

| Parameter | Type | Required | Description |
|---|---|---|---|
| `appointmentId` | UUID | ✅ | Appointment to cancel |

#### Request Body

```json
{
  "status": "CANCELLED",
  "reason": "Customer requested cancellation via mobile application"
}
```

#### `200 OK` — Appointment cancelled

```json
{
  "appointmentId": "aaa00000-1234-5678-abcd-000000000001",
  "status": "CANCELLED"
}
```

#### `404 Not Found` — Appointment not found

```json
{
  "error": "Appointment not found: aaa00000-1234-5678-abcd-000000000001"
}
```

#### `422 Unprocessable Entity` — Invalid state transition

```json
{
  "error": "Cannot transition appointment [aaa00000-...] from CANCELLED to CANCELLED"
}
```

---

## Health & Observability Endpoints

| Endpoint | Description |
|---|---|
| `GET /q/health` | Overall health (readiness + liveness) |
| `GET /q/health/live` | Liveness probe (Kubernetes) |
| `GET /q/health/ready` | Readiness probe (Kubernetes) |
| `GET /q/metrics` | Prometheus metrics |
| `GET /q/swagger-ui` | Interactive OpenAPI UI |
| `GET /q/openapi` | Raw OpenAPI 3.x YAML/JSON spec |

---

## Seed Data Reference (V2__seed_data.sql)

Use these UUIDs in all test requests:

### Dealerships
| Name | ID |
|---|---|
| Keyloop London Flagship | `11111111-0000-0000-0000-000000000001` |
| Keyloop Manchester North | `11111111-0000-0000-0000-000000000002` |
| Keyloop New York Metro | `11111111-0000-0000-0000-000000000003` |

### Service Types (London)
| Name | Duration | Required Skill | ID |
|---|---|---|---|
| Standard Oil Change | 45min | 1 | `22222222-0000-0000-0000-000000000001` |
| Brake Maintenance | 60min | 3 | `22222222-0000-0000-0000-000000000002` |
| Comprehensive Overhaul | 120min | 7 | `22222222-0000-0000-0000-000000000003` |

### Technicians (London)
| Name | Skill Level | ID |
|---|---|---|
| Alice Johnson | 8 (Senior) | `33333333-0000-0000-0000-000000000001` |
| Bob Smith | 4 (Mid) | `33333333-0000-0000-0000-000000000002` |
| Carol Williams | 2 (Junior) | `33333333-0000-0000-0000-000000000003` |

### Service Bays (London)
| Bay | Status | ID |
|---|---|---|
| BAY-A1 | OPERATIONAL | `44444444-0000-0000-0000-000000000001` |
| BAY-A2 | OPERATIONAL | `44444444-0000-0000-0000-000000000002` |
| BAY-A3 | OPERATIONAL | `44444444-0000-0000-0000-000000000003` |

### Customers & Vehicles
| Customer | Vehicle | Customer ID | Vehicle ID |
|---|---|---|---|
| James Wilson | Honda Civic 2022 | `55555555-0000-0000-0000-000000000001` | `66666666-0000-0000-0000-000000000001` |
| Sarah Thompson | Toyota Corolla 2021 | `55555555-0000-0000-0000-000000000002` | `66666666-0000-0000-0000-000000000002` |
