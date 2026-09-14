# CLAUDE.md — Architecture & Engineering Guardrails

## 1. Project Overview
- **Service Name:** `unified-service-scheduler`
- **Domain:** Keyloop Automotive Retail Platform (Scenario A - Ownership Domain)
- **Role:** Enterprise-grade reactive backend managing service appointment reservations and real-time bay/technician availability.
- **Architecture Pattern:** CQRS, Clean Architecture, Reactive Streams, Transactional Outbox Pattern, Event-Driven Architecture (EDA).

---

## 2. Tech Stack Specification
- **Java Platform:** Java 21 (LTS)
- **Framework:** Quarkus 3.x (Quarkus Reactive Engine)
- **Reactive Stream Engine:** SmallRye Mutiny (`Uni<T>`, `Multi<T>`)
- **Internal Messaging:** Eclipse Vert.x EventBus (intra-pod non-blocking decoupled dispatch)
- **Persistence & ORM:** Hibernate Reactive with Panache (PostgreSQL 16+) / Mutiny PG Client
- **Distributed Cache & Locking:** Quarkus Redis Client (Mutiny API) for Idempotency and Distributed Locks
- **Event Streaming:** SmallRye Reactive Messaging, Apache Kafka, Kafka Streams
- **Change Data Capture (CDC):** Debezium (PostgreSQL Outbox Table -> Kafka)
- **Testing:** Testcontainers (PostgreSQL, Redis, Kafka), REST-assured, Mutiny Test Tools

---

## 3. Core Development & Build Commands

```bash
# Development Mode with Live Reload
./mvnw quarkus:dev

# Run Unit Tests & Architecture Rules
./mvnw test

# Run End-to-End & Testcontainers Integration Tests
./mvnw verify -Pintegration-tests

# Native Compilation (GraalVM)
./mvnw package -Dnative -Dquarkus.native.container-build=true

# Spin Up Local Infrastructure (PostgreSQL, Redis, Kafka, Elasticsearch)
docker compose -f docker-compose.dev.yml up -d