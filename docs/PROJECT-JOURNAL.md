# Saga Orchestration Engine -- Project Journal

**Version:** 1.0.0-SNAPSHOT
**Stack:** Java 17, Spring Boot 3.2, MongoDB ReplicaSet, Apache Kafka, Debezium CDC
**Modules:** `saga-orchestrator-core` (engine) + `saga-orchestrator-sdk` (participant library) + `saga-orchestrator-example` (showcase)

---

## Table of Contents

1. [Project Genesis](#1-project-genesis)
2. [Architecture Decisions](#2-architecture-decisions)
3. [Distributed System Challenges and Solutions](#3-distributed-system-challenges-and-solutions)
4. [SDK Design -- Developer Experience Journey](#4-sdk-design----developer-experience-journey)
5. [Bugs Found and Fixed](#5-bugs-found-and-fixed)
6. [Eventuate Tram Comparison](#6-eventuate-tram-comparison)
7. [Scale Limits and Re-engineering Guide](#7-scale-limits-and-re-engineering-guide)
8. [What's Built (File Inventory)](#8-whats-built-file-inventory)
9. [Docker and Infrastructure](#9-docker-and-infrastructure)
10. [v2 Roadmap](#10-v2-roadmap)
11. [Prometheus Metrics](#11-prometheus-metrics)
12. [API Reference](#12-api-reference)
13. [YAML Schema Reference](#13-yaml-schema-reference)

---

## 1. Project Genesis

### What

A production-grade **Saga Orchestration Engine** that manages distributed transactions across microservices. Ships as two Maven/Gradle artifacts:

- **`saga-orchestrator-core`** -- the engine that drives saga instances through defined step sequences, handles compensation, timeouts, and crash recovery.
- **`saga-orchestrator-sdk`** -- a lightweight library that participant microservices import to handle saga commands with minimal boilerplate.

### Why

Company requirement for cross-service transaction management. When an order placement spans inventory, payment, and shipment services, there is no database transaction that covers all three. If payment fails after inventory is reserved, you need a reliable mechanism to release that inventory. This engine provides that mechanism.

### Stack Decisions

| Technology | Version | Why |
|---|---|---|
| Java | 17 (LTS) | Records, sealed classes, `HexFormat`. Corporate standard. |
| Spring Boot | 3.2.0 | `RestClient` (new in 3.2), Jakarta EE, native-compile-ready. |
| MongoDB | 7.0 ReplicaSet | Multi-document transactions + Change Streams (required for Debezium). |
| Apache Kafka | Confluent 7.5.0 | Async event bus for step commands and replies. |
| Debezium | 2.4 | CDC connector watches `saga_outbox` collection, publishes to Kafka. |
| Gradle | Multi-module | Two-artifact design maps naturally to Gradle subprojects. |
| Testcontainers | JUnit 5 | Real MongoDB + Kafka in integration tests -- no mocks for infra. |

### Rejected Alternatives

| Framework | Why Rejected |
|---|---|
| **Eventuate Tram** | Supports only Postgres/MySQL for its transaction outbox. Our stack is MongoDB. Eventuate's JDBC-based outbox simply does not work with MongoDB multi-document transactions or Change Streams. |
| **Temporal** | Full workflow orchestration platform -- massive runtime dependency. Requires its own server cluster (Temporal Server + Cassandra/MySQL). Overkill for saga-only use case. Vendor lock-in on execution model. |
| **Camunda** | BPMN-based process engine. Requires modeling sagas as BPMN diagrams. Introduces XML modeling complexity we do not need. Heavy runtime with its own database. |
| **Axon Framework** | Forces DDD + CQRS + Event Sourcing architecture on the entire codebase. Cannot adopt it for saga orchestration alone without restructuring every participating service around Axon's aggregate and event model. |

**The core reasoning:** All four alternatives force an external runtime, database requirement, or architectural model that conflicts with our constraints (MongoDB, lightweight, on-premise, no forced paradigm shift). Building from scratch gives us exact control over the guarantees we need with zero compromise on the stack.

---

## 2. Architecture Decisions

### 2.1 Saga Orchestration Pattern (not Choreography)

**Decision:** Central orchestrator drives step execution in sequence.

**Why not Choreography:** In choreography, each service publishes events and the next service reacts. This works for 2-3 services but becomes unmanageable at 5-6 participants:
- No single place to see the saga's current state
- Compensation ordering is implicit and distributed across services
- Adding a new step requires modifying multiple services
- Debugging a failed saga requires tracing events across all services

**Why Orchestration:** One engine, one state machine, one place to query status, one place that guarantees reverse-order compensation. The participating services are dumb executors -- they receive a command, do work, reply. They have zero knowledge of the saga flow.

**Trade-off:** Single point of coordination (mitigated by stateless orchestrator instances reading from MongoDB).

---

### 2.2 Transactional Outbox + Debezium CDC (not direct Kafka publish)

**Decision:** Commands are written to `saga_outbox` collection inside a MongoDB transaction alongside the saga state checkpoint. Debezium watches `saga_outbox` via Change Streams and publishes to Kafka.

**Why not direct Kafka publish:** The dual-write problem. If you update MongoDB then publish to Kafka, either can fail independently:
- MongoDB write succeeds, Kafka publish fails -> saga state says "dispatched" but command never sent
- Kafka publish succeeds, MongoDB write fails -> command sent but saga doesn't know about it

There is no transaction spanning MongoDB and Kafka. The outbox pattern eliminates this by making Kafka publishing a separate concern handled by Debezium.

**Trade-off:** Added infrastructure (Debezium connector) and slightly higher latency (CDC poll interval). Mitigated by `OutboxPoller` fallback.

---

### 2.3 MongoDB ReplicaSet (not Postgres)

**Decision:** MongoDB 7.0 with ReplicaSet enabled.

**Why ReplicaSet is mandatory (not optional):**
1. **Multi-document transactions** -- required for atomic write of `SagaInstance` + `OutboxEntry`. MongoDB only supports multi-document transactions on ReplicaSets.
2. **Change Streams** -- Debezium uses MongoDB Change Streams to watch the `saga_outbox` collection. Change Streams require an oplog, which only exists on ReplicaSets.

**Why MongoDB over Postgres:**
- Document model fits saga instances naturally (nested payload, context maps, flexible schema)
- Change Streams are native and lower-latency than Postgres logical replication for CDC
- Team already uses MongoDB in the stack

**Trade-off:** MongoDB's transaction support is newer and less battle-tested than Postgres. ReplicaSet setup is more complex than standalone MongoDB.

---

### 2.4 Single `saga-replies` Topic (not per-service reply topics)

**Decision:** All participant services reply to one topic: `saga-replies`, partitioned by `sagaId`.

**Why not per-service topics (e.g., `inventory-replies`, `payment-replies`):**
- Orchestrator would need one `@KafkaListener` per participating service, or dynamic topic subscription
- Adding a new participant means reconfiguring the orchestrator's consumer
- Partitioning by `sagaId` ensures all replies for one saga land on the same partition -> processed in order by one consumer thread

**Trade-off:** Single topic can become a throughput bottleneck at extreme scale. Mitigated by increasing partition count and consumer concurrency (config-only change, no code change).

---

### 2.5 YAML Saga Definitions (not Java Builder DSL)

**Decision:** Sagas are defined in `src/main/resources/sagas/*.yml` files, loaded once at startup.

```yaml
name: order-placement-saga
timeoutMinutes: 30
steps:
  - name: reserve-inventory
    type: KAFKA
    module: inventory
    action: RESERVE_INVENTORY
    compensationAction: RELEASE_INVENTORY
```

**Why not a Java DSL:**
- YAML is readable by ops, QA, and architects without Java knowledge
- Saga definitions can be reviewed in PRs as configuration, not code
- Decouples saga flow from Java compilation -- future path to dynamic definitions from MongoDB
- Prevents developers from embedding business logic in the definition

**Trade-off:** Less type-safe than Java. Mitigated by `SagaDefinitionValidator` that validates all YAML at startup (fails fast on invalid definitions).

---

### 2.6 Two-Artifact Design: Core + SDK

**Decision:** Ship two separate JARs.

| Artifact | Who imports it | Contains |
|---|---|---|
| `saga-orchestrator-core` | The orchestrator service | Engine, state machine, outbox, executors, API, schedulers |
| `saga-orchestrator-sdk` | Each participant service | `@SagaParticipant`, `@SagaCommandHandler`, `SagaCommand`, `SagaReply`, auto-listener registration |

**Why separation:**
- Participants must NEVER depend on engine internals. If a participant imports core, they get access to `SagaStateMachine`, `OutboxWriter`, `SagaInstanceRepository` -- none of which they should touch.
- SDK is thin: Spring Kafka + Jackson + annotations. No MongoDB, no web, no actuator.
- Enforces the architectural boundary: participants know about commands and replies, nothing else.

**Trade-off:** Two artifacts to version and publish. Worth it for clean dependency boundaries.

---

### 2.7 Checkpoint-Based Resume -- Crash Recovery Strategy

**Decision:** Before dispatching any step, the engine checkpoints the saga's current state to MongoDB. On crash recovery, the engine resumes from the last completed step.

**How it works:**
1. `SagaStateMachine.startStep()` saves `SagaInstance` with `status=IN_PROGRESS` and current `stepIndex`
2. `KafkaStepExecutor.execute()` writes `OutboxEntry` atomically with saga checkpoint via `OutboxWriter.writeAtomic()`
3. If the JVM crashes between step completion and next dispatch, on restart the saga is found as `IN_PROGRESS` at the correct step index
4. `SagaTimeoutScheduler` picks up timed-out sagas and triggers compensation

**Why not event sourcing for recovery:** Event sourcing would give perfect replay but requires restructuring the entire persistence model. Checkpoint-based recovery is simpler: one document read, resume from `currentStep`.

---

### 2.8 `@Version` Optimistic Locking -- Concurrent Saga Handling

**Decision:** `SagaInstance` has `@Version` (Spring Data MongoDB optimistic lock). Concurrent updates to the same saga instance cause `OptimisticLockingFailureException`.

```java
@Version
private Long version;
```

**Why optimistic over pessimistic:** Saga steps are sequential -- concurrent writes to the same saga instance are rare (only during race conditions like duplicate replies). Pessimistic locking would hold MongoDB document locks unnecessarily for every write.

**Trade-off:** Under extreme concurrent reply volume for the same `sagaId`, optimistic lock retries can spike. See Scale Limit #5.

---

## 3. Distributed System Challenges and Solutions

### 3.1 Dual-Write Problem

**The problem:** Updating saga state in MongoDB and publishing a command to Kafka are two separate operations. If either fails independently, the system is inconsistent.

**Why it's hard:** There is no distributed transaction spanning MongoDB and Kafka. Two-phase commit across heterogeneous systems is impractical.

**Our solution:** Transactional Outbox pattern.
- Step command is written to `saga_outbox` collection inside the same MongoDB transaction as the saga state update (`OutboxWriter.writeAtomic()`)
- Debezium CDC watches `saga_outbox` via Change Streams and publishes to Kafka
- The Kafka publish is eventually consistent but guaranteed -- if it's in MongoDB, Debezium will publish it

**Limitations:**
- Added latency: Debezium poll interval (typically 500ms-1s)
- Debezium is a single point of failure (mitigated by `OutboxPoller` fallback)
- Outbox entries accumulate and need TTL-based cleanup

---

### 3.2 Idempotency -- Duplicate Saga Creation

**The problem:** The same request can arrive twice (network retry, user double-click, load balancer retry). Without protection, two saga instances execute the same work.

**Why it's hard:** The check-then-insert pattern has a race window. Two threads check "does this saga exist?" simultaneously, both get "no", both insert.

**Our solution:** SHA-256 hash + MongoDB unique index + DuplicateKeyException handling.

```java
// IdempotencyGuard.java
public String computeKey(String sagaType, Map<String, Object> payload) {
    String input = sagaType + ":" + new TreeMap<>(payload);
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
    return HexFormat.of().formatHex(hash);
}

public SagaInstance saveOrGetExisting(SagaInstance instance) {
    try {
        return repository.save(instance);
    } catch (DuplicateKeyException e) {
        return repository.findByIdempotencyKey(instance.getIdempotencyKey())
                .orElseThrow(...);
    }
}
```

- `saga_instances.idempotencyKey` has a unique index in MongoDB
- Hash includes `sagaType` so different saga types with same payload are distinct
- `TreeMap` ensures key ordering is deterministic regardless of insertion order
- `DuplicateKeyException` catch handles the race condition atomically at the database level

**Limitations:** Hash collisions are theoretically possible with SHA-256 but probability is negligible (1 in 2^256).

---

### 3.3 Failure Classification

**The problem:** Not all failures are equal. "Card declined" means stop and reverse. "Database timeout" means retry and maybe try again later.

**Why it's hard:** The orchestrator receives a failure reply but doesn't know the participant's internal context. The classification must be made by the participant and propagated correctly.

**Our solution:** Two failure types with distinct code paths.

| Type | Example | Orchestrator Action |
|---|---|---|
| `BUSINESS` | Card declined, out of stock, insufficient funds | Compensate immediately -- reverse all completed steps |
| `TECHNICAL` | DB timeout, service unavailable, network error | Retry (Spring Kafka backoff) -> DLT -> SUSPEND |

The SDK provides explicit exception types:
```java
throw new SagaBusinessException("OUT_OF_STOCK");   // -> compensate
throw new SagaTechnicalException("DB_UNAVAILABLE"); // -> retry -> suspend
```

Plus auto-classification for untyped exceptions:
- `IllegalArgumentException`, `IllegalStateException` -> BUSINESS (bad input)
- `NullPointerException`, `ClassCastException` -> TECHNICAL (code bug)
- `IOException`, `TimeoutException` -> TECHNICAL (infra problem)
- Everything else -> TECHNICAL (safe default)

**Limitations:** Auto-classification is heuristic. Edge cases where `IllegalArgumentException` is actually a technical problem will be misclassified. Developers should use explicit exception types.

---

### 3.4 Compensation Ordering

**The problem:** When step 3 fails, steps 1 and 2 must be undone. But they must be undone in reverse order (2 then 1), because step 2 may depend on step 1's side effects being present.

**Why it's hard:** In choreography, compensation ordering is distributed and implicit. A missed event or reordered message can cause compensations to run out of order.

**Our solution:** Engine-guaranteed strict reverse order. The `SagaOrchestrator.compensate()` method iterates from `currentStep - 1` down to `0`:

```java
private void compensate(SagaInstance instance, SagaDefinition definition) {
    int startFrom = instance.getCurrentStep() - 1;
    for (int i = startFrom; i >= 0; i--) {
        StepDefinition step = definition.steps().get(i);
        if (!step.hasCompensation()) continue;
        executorRegistry.getExecutor(step.type()).compensate(instance, step);
    }
}
```

Steps without a `compensationAction` are skipped (e.g., read-only steps). Compensation failures are recorded per-step but do NOT stop the remaining compensations -- the engine attempts all and reports partial failure via `FAILED` status.

**Limitations:** Kafka compensations in v1 are fire-and-forget. The engine publishes the compensation command but does not wait for a reply. This means if a compensation fails at the participant, the engine doesn't know. Tracked as v2 improvement.

---

### 3.5 Crash Recovery

**The problem:** The orchestrator JVM can crash between completing a step and dispatching the next one. The saga is left in an intermediate state.

**Why it's hard:** There is no single atomic operation that spans "complete step N, advance pointer, dispatch step N+1". The checkpoint must be durable before the dispatch.

**Our solution:** Checkpoint before dispatch.

1. `SagaStateMachine.startStep()` persists the saga state to MongoDB BEFORE `KafkaStepExecutor.execute()` writes to the outbox
2. `KafkaStepExecutor.execute()` uses `OutboxWriter.writeAtomic()` to write the outbox entry atomically with the saga checkpoint
3. On restart, `SagaTimeoutScheduler` finds sagas that are `IN_PROGRESS` but have exceeded their timeout -> triggers compensation
4. For `retryFromSuspended()`, the engine explicitly checkpoints BEFORE dispatching (bug #3 fix):

```java
public void retryFromSuspended(String sagaId) {
    instance.setStatus(SagaStatus.IN_PROGRESS);
    instance = checkpointStore.save(instance);  // checkpoint FIRST
    dispatchStep(instance, definition);          // dispatch AFTER
}
```

**Limitations:** If a crash happens after dispatching but before the participant replies, the saga will time out and compensate -- even though the step may have succeeded. This is acceptable: compensation handlers must be idempotent.

---

### 3.6 Aggregate Locking

**The problem:** Two sagas for the same order (e.g., placement and cancellation) can run concurrently and conflict.

**Why it's hard:** Optimistic locking on `SagaInstance` only prevents concurrent writes to the same saga. It does not prevent two different sagas from operating on the same business entity.

**Our solution:** `SagaLockManager` with auto-expiry.

```yaml
# In saga YAML:
lockTargetType: order
lockTargetField: orderId
```

- Before starting, the engine acquires a lock: `order:ORD-123`
- Lock is stored in `saga_locks` collection with a unique index on `lockKey`
- `DuplicateKeyException` = lock held by another saga -> fail fast with `SagaLockException`
- Expired locks are automatically replaced (handles crashed sagas that never released)
- Background cleanup runs every 5 minutes (`@Scheduled`)
- Locks are released on `COMPLETED`, `COMPENSATED`, or `FAILED`

**Limitations:** Lock granularity is per-entity. If two saga types need different lock scopes on the same entity, the current design doesn't support hierarchical locks.

---

### 3.7 Participant Idempotency

**The problem:** Kafka delivers at-least-once. The same command can arrive at a participant multiple times.

**Why it's hard:** The participant must produce the same result for the same command without re-executing the business logic (which may have side effects like charging a card twice).

**Our solution:** `@Idempotent` annotation + `ProcessedCommand` collection with TTL.

```java
@Idempotent
@SagaCommandHandler(action = "RESERVE_INVENTORY")
public SagaReply reserve(SagaCommand command) { ... }
```

- Before executing, the SDK checks `processed_commands` collection for key `{sagaId}:{stepId}:{action}`
- If found, returns the cached reply JSON without re-executing
- After successful execution, stores the reply in `processed_commands`
- TTL index auto-deletes entries after a configurable period

**Limitations:** `ProcessedCommand` requires MongoDB on the participant side. If a participant doesn't use MongoDB, `@Idempotent` is a no-op (the `ProcessedCommandRepository` is optional via `Optional<>` injection).

---

### 3.8 At-Least-Once Delivery -- Reply Deduplication

**The problem:** The orchestrator's `ReplyCorrelator` can receive the same reply twice (Kafka rebalance, consumer restart before offset commit).

**Why it's hard:** Re-processing a reply would advance the state machine again, potentially dispatching the next step twice.

**Our solution:** `currentStep` check in `SagaOrchestrator.handleReply()`:

```java
StepDefinition currentStep = definition.steps().get(stepIndex);
if (!currentStep.name().equals(stepId)) {
    log.warn("[sagaId={}] Reply stepId={} != current step={} -- ignoring", ...);
    return;
}
```

If the saga has already advanced past this step, the reply is a duplicate and is silently ignored. The `stepId` in the reply must match the saga's `currentStep` -- this is a natural deduplication gate.

**Limitations:** This only works because saga steps are strictly sequential. If we add parallel step execution (v2), this check needs redesign.

---

### 3.9 Debezium Outage -- Fallback Publisher

**The problem:** If Debezium is down, outbox entries pile up in MongoDB and commands never reach Kafka.

**Why it's hard:** The engine has no direct visibility into whether Debezium is functioning. The outbox entries just sit there.

**Our solution:** `OutboxPoller` -- a fallback publisher that runs on `@Scheduled(fixedDelay=30s)`.

```java
@Scheduled(fixedDelayString = "${saga.outbox.poll-interval-ms:30000}")
public void poll() {
    Instant threshold = Instant.now(clock).minusSeconds(5 * 60);
    List<OutboxEntry> pending = outboxRepository.findPendingOlderThan(threshold);
    for (OutboxEntry entry : pending) {
        ProducerRecord<String, String> record = new ProducerRecord<>(
                entry.getTopic(), entry.getMessageKey(), entry.getPayload());
        record.headers().add(new RecordHeader("X-Saga-Id", ...));
        record.headers().add(new RecordHeader("X-Saga-Step-Id", ...));
        kafkaTemplate.send(record);
        entry.setStatus(OutboxStatus.PUBLISHED);
        outboxRepository.save(entry);
    }
}
```

- Only picks up PENDING entries older than 5 minutes (gives Debezium time to publish normally)
- Includes Kafka headers (`X-Saga-Id`, `X-Saga-Step-Id`) so `ReplyCorrelator` works correctly
- Marks entries as `PUBLISHED` after successful send

**Limitations:** At 100+ orchestrator instances, all polling simultaneously can create duplicate publishes. See Scale Limit #2.

---

### 3.10 Timeout Handling

**The problem:** If a participant never replies (crash, network partition, infinite loop), the saga hangs forever.

**Why it's hard:** The orchestrator doesn't know if the participant is still processing or dead. It must wait long enough for slow-but-alive participants, but not so long that failed sagas block indefinitely.

**Our solution:** `SagaTimeoutScheduler` with injectable `Clock`.

- Each saga has a `timeoutAt` field computed at creation: `now + timeoutMinutes`
- `@Scheduled(fixedDelay=60s)` polls for `IN_PROGRESS` sagas where `timeoutAt < now()`
- Timed-out sagas trigger compensation via `orchestrator.triggerCompensation(sagaId)`
- `Clock` bean is injectable -- tests use `Clock.fixed()` to avoid real-time waiting

**Limitations:** Thundering herd at scale. See Scale Limit #1.

---

### 3.11 Concurrent Saga Creation

**The problem:** Two identical requests arrive at different orchestrator instances at the exact same millisecond.

**Why it's hard:** Both pass the `findExisting()` check (neither exists yet), both try to insert.

**Our solution:** `saveOrGetExisting()` with `DuplicateKeyException` catch:

```java
public SagaInstance saveOrGetExisting(SagaInstance instance) {
    try {
        return repository.save(instance);
    } catch (DuplicateKeyException e) {
        return repository.findByIdempotencyKey(instance.getIdempotencyKey())
                .orElseThrow(...);
    }
}
```

The winner creates the saga. The loser catches `DuplicateKeyException` and returns the winner's saga. The caller in `SagaOrchestrator.start()` then checks:

```java
if (instance.getStatus() != SagaStatus.STARTED || instance.getCurrentStep() > 0) {
    return instance; // concurrent creation -- return winner's saga
}
```

This ensures only one instance ever drives the saga forward.

---

## 4. SDK Design -- Developer Experience Journey

### The Problem: 57 Lines of Boilerplate

Before the SDK, every participant service had to write this for EACH command handler:

```java
// BEFORE SDK -- per participant handler
@KafkaListener(topics = "inventory-commands", groupId = "inventory-group")
public void handle(ConsumerRecord<String, String> record) {
    ObjectMapper mapper = new ObjectMapper();
    SagaCommand command = mapper.readValue(record.value(), SagaCommand.class);

    SagaReply reply;
    try {
        // ... actual business logic ...
        int available = inventoryService.checkStock(command.payload().get("productId"));
        if (available < requestedQty) {
            reply = new SagaReply(command.sagaId(), command.stepId(),
                    "BUSINESS_FAILURE", FailureType.BUSINESS, null, "OUT_OF_STOCK");
        } else {
            inventoryService.reserve(productId, qty);
            reply = new SagaReply(command.sagaId(), command.stepId(),
                    "SUCCESS", null, Map.of("reservationId", resId), null);
        }
    } catch (Exception e) {
        reply = new SagaReply(command.sagaId(), command.stepId(),
                "TECHNICAL_FAILURE", FailureType.TECHNICAL, null, e.getMessage());
    }

    String replyJson = mapper.writeValueAsString(reply);
    ProducerRecord<String, String> producerRecord =
            new ProducerRecord<>("saga-replies", command.sagaId(), replyJson);
    producerRecord.headers().add(new RecordHeader("X-Saga-Id",
            command.sagaId().getBytes(StandardCharsets.UTF_8)));
    producerRecord.headers().add(new RecordHeader("X-Saga-Step-Id",
            command.stepId().getBytes(StandardCharsets.UTF_8)));
    producerRecord.headers().add(new RecordHeader("X-Saga-Status",
            reply.status().getBytes(StandardCharsets.UTF_8)));
    if (reply.failureType() != null) {
        producerRecord.headers().add(new RecordHeader("X-Failure-Type",
                reply.failureType().name().getBytes(StandardCharsets.UTF_8)));
    }
    kafkaTemplate.send(producerRecord);
}
```

Every handler repeated: JSON deserialization, try-catch, reply construction, header propagation, Kafka template usage. Multiply by number of actions per service.

### The Solution: 5 Lines of Business Logic

```java
// AFTER SDK
@Component
@SagaParticipant(module = "inventory")
public class InventoryCommandHandler {

    @Idempotent
    @SagaCommandHandler(action = "RESERVE_INVENTORY")
    public SagaReply reserve(SagaCommand command) {
        String productId = (String) command.payload().get("productId");
        int qty = ((Number) command.payload().get("quantity")).intValue();
        if (stock.getOrDefault(productId, 0) < qty) {
            throw new SagaBusinessException("OUT_OF_STOCK");
        }
        stock.put(productId, stock.get(productId) - qty);
        return SagaReply.success(command, Map.of("reservationId", "RES-" + command.sagaId().substring(0, 8)));
    }
}
```

The developer writes ONLY business logic. The SDK handles:
- Kafka listener registration (topic derived from `module`)
- JSON deserialization of `SagaCommand`
- Reply serialization and header propagation
- Exception classification (business vs technical)
- Reply publishing to `saga-replies` topic
- Idempotency check and caching (if `@Idempotent`)

### Implementation: Approach B -- BeanPostProcessor

**Three approaches were considered:**

| Approach | Mechanism | Verdict |
|---|---|---|
| A: AOP/Proxy | `@Around` advice intercepts handler calls | Rejected: requires proxying, complex when handler is also a Kafka listener |
| B: BeanPostProcessor | Scans beans at startup, registers Kafka listeners dynamically | **Chosen**: clean separation, works with any bean |
| C: Template Method | Abstract base class with `handle(SagaCommand)` | Rejected: forces inheritance, cannot have multiple handlers per class |

**How `SagaCommandHandlerRegistrar` (BeanPostProcessor) works:**

1. After each bean is initialized, checks for `@SagaParticipant` annotation
2. Scans all methods for `@SagaCommandHandler` annotations
3. Groups handlers by resolved topic (from `module` or explicit `topic`)
4. Creates a `SagaCommandListenerAdapter` per topic
5. Registers a `MethodKafkaListenerEndpoint` that delegates to the adapter
6. The adapter handles: deserialization, handler dispatch, exception classification, reply publishing, idempotency

### Smart Exception Classification

The `SagaCommandListenerAdapter.classifyException()` method:

```
SagaBusinessException         -> businessFailure (developer-explicit)
SagaTechnicalException        -> technicalFailure (developer-explicit)
IllegalArgumentException      -> businessFailure (bad input = business problem)
IllegalStateException         -> businessFailure (invalid state = business rule)
NullPointerException          -> technicalFailure (code bug)
ClassCastException            -> technicalFailure (code bug)
IOException, TimeoutException -> technicalFailure (infra problem)
Everything else               -> technicalFailure (safe default)
```

### Module-Based Topic Convention

Topic is derived automatically from module name:

```
@SagaParticipant(module = "inventory")  ->  listens on "inventory-commands"
@SagaParticipant(module = "payment")    ->  listens on "payment-commands"
```

Can be overridden per-handler: `@SagaCommandHandler(topic = "custom-topic")`.
Can also be set in `application.yml`: `saga.participant.module: inventory`.

### Action-Based Routing

One class, multiple actions:

```java
@SagaCommandHandler(action = "RESERVE_INVENTORY")
public SagaReply reserve(SagaCommand cmd) { ... }

@SagaCommandHandler(action = "RELEASE_INVENTORY")
public SagaReply release(SagaCommand cmd) { ... }
```

Both listen on the same topic. The adapter routes by `command.action()`.

### ParticipantValidator -- Startup Cross-Check

At startup, `ParticipantValidator` fetches saga definitions from the orchestrator via `GET /saga-definitions` and validates:

1. **Module exists in YAML:** The participant's module name appears in at least one saga step
2. **Actions match:** Every action defined in YAML for this module has a local handler
3. **Typo detection:** Local handlers that match no YAML action are flagged

Configuration:
```yaml
saga:
  orchestrator:
    url: http://localhost:8080          # orchestrator base URL
  participant:
    module: inventory
    strict-validation: false             # true = fail startup on mismatch
```

If orchestrator is unreachable, validation is skipped with a WARNING (allows independent dev/testing). Set `strict-validation: true` for production.

---

## 5. Bugs Found and Fixed

### Bug #1: OutboxWriter Not Atomic with SagaInstance

**What:** `OutboxWriter.write()` only saved the `OutboxEntry`. The `SagaInstance` checkpoint and outbox write were two separate MongoDB operations -- not in the same transaction.

**Impact:** If the JVM crashed after checkpoint but before outbox write, the saga state said "step dispatched" but no command was written to the outbox. The step would never execute.

**Fix:** Added `OutboxWriter.writeAtomic()` method that saves both `SagaInstance` and `OutboxEntry` in one `@Transactional` method. `KafkaStepExecutor` now calls `writeAtomic()` instead of `write()`.

---

### Bug #2: IdempotencyGuard Missing DuplicateKeyException Handling

**What:** `IdempotencyGuard` used `findExisting()` then `repository.save()`. Between these two calls, a concurrent request could insert with the same key. The second insert would throw `DuplicateKeyException` and the request would fail with 500.

**Impact:** Under concurrent load, duplicate saga creation requests would intermittently fail instead of returning the existing saga.

**Fix:** Added `saveOrGetExisting()` method that catches `DuplicateKeyException` and returns the existing saga.

---

### Bug #3: retryFromSuspended -- No Checkpoint Before Dispatch

**What:** `retryFromSuspended()` changed the status to `IN_PROGRESS` and dispatched immediately without saving to MongoDB first.

**Impact:** If the JVM crashed after dispatch but before the save, the saga would still be `SUSPENDED` in MongoDB but the step command was already sent. On restart, the timeout scheduler would try to compensate a saga that had a step in-flight.

**Fix:** Added `checkpointStore.save(instance)` before `dispatchStep()`. Verified with unit test that checkpoint happens before dispatch (using `inOrder` verification).

---

### Bug #4: HttpStepExecutor Didn't Return Result to Orchestrator

**What:** Early `HttpStepExecutor.execute()` returned `void`. The orchestrator had no way to know if the HTTP step succeeded or failed.

**Impact:** HTTP steps would execute but the orchestrator could never advance -- the saga would hang until timeout.

**Fix:** Changed return type to `StepResult`. `HttpStepExecutor` now returns `StepResult.success(data)`, `StepResult.businessFailure(error)`, or `StepResult.technicalFailure(error)`. The `SagaOrchestrator.dispatchStep()` handles HTTP results inline (unlike Kafka which is async).

---

### Bug #5: OutboxPoller Published Without Kafka Headers

**What:** `OutboxPoller` (fallback publisher) sent `ProducerRecord` without `X-Saga-Id` and `X-Saga-Step-Id` headers.

**Impact:** When Debezium was down and the poller kicked in, replies from participants could not be correlated back to the saga. `ReplyCorrelator` would log "missing saga headers" and skip the reply.

**Fix:** Added `RecordHeader` attachments for `X-Saga-Id` and `X-Saga-Step-Id` in `OutboxPoller.poll()`.

---

### Bug #6: @Scheduled Property Names -- Wrong Namespace

**What:** `@Scheduled` annotations used property names like `${outbox.poll-interval-ms:30000}` and `${timeout.poll-interval-ms:60000}` without the `saga.` prefix.

**Impact:** Properties set in `application.yml` under `saga.outbox.poll-interval-ms` were ignored. The schedulers always used default values.

**Fix:** Changed all `@Scheduled` property references to use `saga.*` prefix:
- `${saga.outbox.poll-interval-ms:30000}`
- `${saga.timeout.poll-interval-ms:60000}`
- `${saga.lock.cleanup-interval-ms:300000}`

---

### Bug #7: SDK @SagaCommandHandler Was Decorative (No-Op)

**What:** The `@SagaCommandHandler` annotation existed but nothing scanned for it. It was purely decorative -- participant handlers were not registered as Kafka listeners.

**Impact:** Participants would compile and start successfully but never receive any commands. Silent failure.

**Fix:** Implemented `SagaCommandHandlerRegistrar` as a `BeanPostProcessor` that scans for `@SagaParticipant` beans, discovers `@SagaCommandHandler` methods, and dynamically registers `MethodKafkaListenerEndpoint` instances.

---

### Bug #8: bootJar Enabled on Core Library Module

**What:** `saga-orchestrator-core/build.gradle` had the `org.springframework.boot` plugin applied but didn't disable `bootJar`. Gradle produced a fat JAR with a `BOOT-INF` layout instead of a plain library JAR.

**Impact:** Any service that added `saga-orchestrator-core` as a dependency couldn't see the classes -- they were nested inside `BOOT-INF/classes/`.

**Fix:** Added to `saga-orchestrator-core/build.gradle`:
```gradle
bootJar { enabled = false }
jar { enabled = true }
```

---

### Bug #9: Docker Compose Port Conflicts

**What:** Docker compose used default ports (27017, 9092, 2181, 8083) that conflicted with existing MongoDB, Kafka, Zookeeper, and Debezium containers on the dev machine.

**Impact:** `docker-compose up` failed with "port already in use" errors.

**Fix:** Remapped all ports to non-default values:

| Service | Default Port | Mapped Port |
|---|---|---|
| MongoDB | 27017 | 27117 |
| Kafka | 9092 | 9192 |
| Zookeeper | 2181 | 2281 |
| Debezium | 8083 | 8183 |

---

### Bug #10: MongoDB Standalone Instead of ReplicaSet

**What:** Docker compose started MongoDB as a standalone instance without ReplicaSet configuration.

**Impact:** Multi-document transactions threw `IllegalStateException: Sessions are not supported by the MongoDB cluster`. Debezium failed with "Change Streams require a replica set".

**Fix:** Replaced simple MongoDB container with inline entrypoint that:
1. Starts `mongod` with `--replSet rs0`
2. Waits for mongod to be ready
3. Runs `rs.initiate()` for single-node ReplicaSet
4. Waits for PRIMARY state
5. Creates root user
6. Healthcheck verifies `rs.status().ok`

---

### Bug #11: Kafka Compensation Fire-and-Forget (Known Limitation)

**What:** `KafkaStepExecutor.compensate()` writes to outbox but does not wait for a reply from the participant.

**Impact:** If the compensation command fails at the participant (e.g., inventory release fails), the orchestrator doesn't know. The saga is marked `COMPENSATED` even though some compensations may have failed silently.

**Status:** Documented as v2 limitation. v1 compensations are best-effort. Full compensation confirmation requires a reply loop similar to forward steps.

---

## 6. Eventuate Tram Comparison

| Feature | Eventuate Tram | This Engine | Notes |
|---|---|---|---|
| **Database** | Postgres/MySQL only | MongoDB ReplicaSet | Tram's outbox is JDBC-based |
| **Outbox pattern** | JDBC outbox table | MongoDB outbox collection + Debezium | Same concept, different implementation |
| **CDC mechanism** | Polling or Debezium | Debezium + OutboxPoller fallback | We have a fallback; Tram relies solely on its chosen mechanism |
| **Saga definition** | Java DSL (SagaBuilder) | YAML declarative | YAML is more accessible; Java DSL is more type-safe |
| **Step types** | Kafka only | Kafka + HTTP | We support synchronous HTTP steps natively |
| **Failure classification** | Single failure type | BUSINESS vs TECHNICAL | We differentiate compensation-worthy vs retry-worthy failures |
| **Compensation confirmation** | Waits for compensation reply | Fire-and-forget (v1) | **Gap** -- Tram is stronger here |
| **Participant SDK** | `SagaCommandHandlersBuilder` | `@SagaParticipant` + `@SagaCommandHandler` | Annotation-based vs builder-based |
| **Participant idempotency** | Not built-in | `@Idempotent` + `ProcessedCommand` collection | **Advantage** -- built into SDK |
| **Participant validation** | Not built-in | `ParticipantValidator` startup cross-check | **Advantage** -- catches misconfig at startup |
| **Aggregate locking** | Not built-in | `SagaLockManager` with auto-expiry | **Advantage** -- prevents concurrent sagas on same entity |
| **Observability API** | Separate service | Auto-configured in core module | 4 endpoints out of the box |
| **Timeout handling** | Application-level | Built-in `SagaTimeoutScheduler` | **Advantage** -- first-class concern |
| **Crash recovery** | Event-driven replay | Checkpoint-based resume | Different approaches, both valid |
| **Metrics** | Not built-in | Micrometer/Prometheus | **Advantage** -- 8 metrics out of the box |
| **Parallel steps** | Supported | Not yet (v2) | **Gap** -- Tram supports parallel execution |
| **Nested sagas** | Not built-in | Not yet (v2) | Equal |
| **Maturity** | Production-proven, open source | New, internal | **Gap** -- Tram has years of battle-testing |

**Summary:** We are equal or ahead on most operational features (locking, timeouts, metrics, SDK DX, participant validation). We have two gaps: compensation confirmation and parallel step execution, both tracked for v2.

---

## 7. Scale Limits and Re-engineering Guide

### Limit 1: Timeout Scheduler -- Thundering Herd

**v1:** `@Scheduled(fixedDelay=60s)` on every orchestrator instance polls MongoDB for timed-out sagas.

**Breaks at:** ~50,000 concurrent IN_PROGRESS sagas across 10+ orchestrator instances. All instances query simultaneously -- MongoDB contention spikes every 60 seconds.

**Re-engineer to:** Delayed Kafka messages.
```
On saga creation -> publish to saga-timeout-events with timestamp = now() + timeoutMinutes
Kafka Streams processes when event time arrives -> triggers compensation
Zero polling. Zero thundering herd. Scales to millions.
```

---

### Limit 2: OutboxPoller -- Duplicate Publishing Risk

**v1:** Simple poll -- query PENDING entries older than 5 minutes, publish, mark PUBLISHED.

**Breaks at:** 100+ orchestrator instances all polling simultaneously. Race between PENDING->PUBLISHED check and publish creates duplicate Kafka events.

**Re-engineer to:** Competitive claiming with atomic MongoDB `findAndModify`.
```
PENDING -> CLAIMED (atomically, with instance ID + claimedAt timestamp)
        -> PUBLISHED (after successful Kafka publish)
        -> stale CLAIMED entries auto-reset to PENDING after TTL
```
`CLAIMED` state already exists in `OutboxEntry` schema -- just implement the claiming logic.

---

### Limit 3: Reply Consumer -- Throughput Ceiling

**v1:** Single `ReplyCorrelator`, `ConcurrentMessageListenerContainer` with concurrency=1.

**Breaks at:** Reply throughput exceeds single-thread processing (~5,000-10,000 replies/sec).

**Re-engineer to:** Increase `spring.kafka.listener.concurrency` property. No code change. Match concurrency to `saga-replies` partition count.

---

### Limit 4: MongoDB Single ReplicaSet -- Storage and Write Ceiling

**v1:** Single MongoDB ReplicaSet for all collections.

**Breaks at:** ~50M saga instances or ~10,000-50,000 writes/second.

**Re-engineer to:** MongoDB Sharded Cluster.
```
Shard key for saga_instances:      { sagaId: "hashed" }
Shard key for saga_outbox:         { sagaId: "hashed" }
Shard key for saga_execution_log:  { sagaId: "hashed" }
```
UUID v4 `sagaId` ensures even distribution. Same shard key for `saga_instances` and `saga_outbox` guarantees they land on the same shard (required for multi-document transactions).

---

### Limit 5: Optimistic Locking Retry Storms

**v1:** `@Version` on `SagaInstance` -- concurrent updates retry on version conflict.

**Breaks at:** Extreme concurrent reply volume for the same `sagaId` (rare in practice).

**Re-engineer to:** Consistent hashing ownership ring. Each orchestrator instance owns a range of `sagaId` hash space. No cross-instance contention. Major rearchitecting effort.

---

### Limit 6: HTTP Step -- No Circuit Breaker

**v1:** `RestClient` with connection/read timeout per step. Slow service = threads waiting.

**Breaks at:** Degraded downstream HTTP service under high saga throughput -- thread pool exhaustion.

**Re-engineer to:** Resilience4j `@CircuitBreaker` per HTTP endpoint. Dependency already in `build.gradle`, annotations present but disabled. Enable by configuration -- no structural change.

---

### Limit 7: Saga Definition -- In-Memory, Static

**v1:** YAML loaded once at startup, immutable for JVM lifetime.

**Breaks at:** Need to update definitions without redeploying. Multi-tenant scenarios.

**Re-engineer to:** Dynamic definition store in MongoDB.
```
saga_definitions collection:
{ name, version, steps[], status: ACTIVE/DEPRECATED, createdAt }

SagaInstance stores { sagaType, sagaDefinitionVersion }
In-flight sagas always use the version they started with
New triggers use the latest ACTIVE version
```

---

### Scale Evolution Summary

| Component | v1 Limit | Re-engineer When | Effort |
|---|---|---|---|
| SagaTimeoutScheduler | ~50K concurrent sagas | Scheduler CPU/MongoDB spikes | High |
| OutboxPoller | ~100 instances | Duplicate publish rate measurable | Medium (schema ready) |
| ReplyCorrelator | ~10K replies/sec | Consumer lag growing | Low (config change) |
| MongoDB ReplicaSet | ~50M sagas / ~50K writes/sec | Storage/write metrics | Medium (infra change) |
| Optimistic locking | Extreme concurrency | Retry rate in metrics | High |
| HTTP circuit breaker | Thread pool exhaustion | Timeout errors spike | Low (dependency ready) |
| Static saga definitions | Redeployment required | Multi-tenant need | Medium |

---

## 8. What's Built (File Inventory)

### Module Structure

```
saga-orchestration-engine/
|-- build.gradle                          Root build config (Spring Boot 3.2.0, Java 17)
|-- settings.gradle                       Module declarations
|-- docker-compose.yml                    Local dev infrastructure
|-- .github/workflows/ci.yml             CI pipeline
|-- docker/
|   |-- mongodb/init-replica.sh           MongoDB ReplicaSet init
|   |-- debezium/register-connector.json  Debezium CDC connector config
|-- saga-orchestrator-core/               THE ENGINE
|-- saga-orchestrator-sdk/                PARTICIPANT LIBRARY
|-- saga-orchestrator-example/            SHOWCASE APP
```

### saga-orchestrator-core (49 source files)

```
com.anirudh.saga.core/
|-- SagaOrchestratorAutoConfiguration.java    Spring Boot auto-config entry point
|
|-- domain/
|   |-- SagaInstance.java           Mutable saga state (@Document, @Version, context map)
|   |-- SagaDefinition.java        Immutable record (name, timeout, steps, lock config)
|   |-- StepDefinition.java        Immutable record (name, type, action, URLs, module)
|   |-- SagaStep.java              Step execution state
|   |-- SagaStatus.java            Enum: STARTED, IN_PROGRESS, COMPLETED, COMPENSATING, COMPENSATED, SUSPENDED, FAILED
|   |-- StepType.java              Enum: KAFKA, HTTP
|
|-- engine/
|   |-- SagaOrchestrator.java      Public API: start(), handleReply(), retryFromSuspended(), triggerCompensation()
|   |-- SagaStateMachine.java      State transitions + checkpoint persistence + metrics
|   |-- IdempotencyGuard.java      SHA-256 hash + DuplicateKeyException catch
|   |-- CheckpointStore.java       Wraps SagaInstanceRepository + SagaExecutionLogRepository
|   |-- FailureClassifier.java     BUSINESS vs TECHNICAL classification
|
|-- executor/
|   |-- StepExecutor.java          Interface: execute(), compensate(), supports()
|   |-- KafkaStepExecutor.java     Writes to outbox (atomic), returns StepResult.dispatched()
|   |-- HttpStepExecutor.java      RestClient POST, returns StepResult inline
|   |-- StepExecutorRegistry.java  Routes StepType -> StepExecutor
|   |-- ReplyCorrelator.java       @KafkaListener on saga-replies, delegates to SagaOrchestrator
|   |-- StepResult.java            Record: DISPATCHED / SUCCESS / BUSINESS_FAILURE / TECHNICAL_FAILURE
|
|-- outbox/
|   |-- OutboxWriter.java          write() and writeAtomic() methods (@Transactional)
|   |-- OutboxEntry.java           MongoDB document: sagaId, stepId, topic, payload, status
|   |-- OutboxPoller.java          Fallback publisher (@Scheduled 30s)
|   |-- OutboxStatus.java          Enum: PENDING, PUBLISHED (CLAIMED reserved for v2)
|
|-- loader/
|   |-- SagaDefinitionLoader.java      Scans classpath:sagas/*.yml, caches in memory
|   |-- SagaDefinitionValidator.java   Validates YAML structure at startup
|
|-- scheduler/
|   |-- SagaTimeoutScheduler.java  @Scheduled 60s, finds timed-out sagas, triggers compensation
|
|-- dlt/
|   |-- DltHandler.java            @KafkaListener on *.DLT, classifies and routes to orchestrator
|
|-- lock/
|   |-- SagaLockManager.java       Acquire/release/cleanup with DuplicateKeyException + auto-expiry
|   |-- SagaLock.java              MongoDB document: lockKey, sagaId, expiresAt
|   |-- SagaLockRepository.java    Spring Data MongoDB repository
|
|-- repository/
|   |-- SagaInstanceRepository.java
|   |-- SagaOutboxRepository.java
|   |-- SagaExecutionLogRepository.java
|
|-- api/
|   |-- SagaController.java            REST: POST/GET /sagas, retry, compensate
|   |-- SagaDefinitionController.java  REST: GET /saga-definitions (for SDK validation)
|   |-- SagaResponse.java              Standard wrapper: { success, data, error }
|   |-- SagaExceptionHandler.java      @RestControllerAdvice for all exception types
|
|-- exception/
|   |-- SagaNotFoundException.java
|   |-- SagaAlreadyExistsException.java
|   |-- SagaInvalidStateException.java
|   |-- SagaExecutionException.java
|   |-- SagaDefinitionNotFoundException.java
|   |-- SagaDefinitionInvalidException.java
|   |-- SagaLockException.java
|
|-- metrics/
|   |-- SagaMetrics.java           8 Micrometer metrics (counters + timer)
|
|-- config/
|   |-- ClockConfig.java           Injectable Clock bean (testable time)
|   |-- KafkaConfig.java           Kafka consumer/producer factories
|   |-- MongoConfig.java           MongoDB transaction manager
|
|-- infrastructure/
|   |-- MongoIndexConfig.java      ensureIndex() on startup (idempotent)
|
|-- audit/
|   |-- SagaExecutionLog.java      Append-only log entry (sagaId, stepName, event, timestamp)
```

### saga-orchestrator-sdk (18 source files)

```
com.anirudh.saga.sdk/
|-- annotation/
|   |-- SagaParticipant.java           Class-level: module name
|   |-- SagaCommandHandler.java        Method-level: action, topic, groupId
|   |-- Idempotent.java                Method-level: enable processed-command caching
|
|-- contract/
|   |-- SagaCommand.java               Record: sagaId, stepId, action, payload
|   |-- SagaReply.java                 Record with factory methods: success(), businessFailure(), technicalFailure()
|   |-- SagaHttpCommand.java           Record for HTTP step commands
|   |-- SagaHttpReply.java             Record for HTTP step replies
|   |-- SagaStartRequest.java          Record: sagaType, payload, idempotencyKey
|   |-- FailureType.java               Enum: BUSINESS, TECHNICAL
|   |-- SagaHeaders.java               Kafka header constants + utilities
|
|-- config/
|   |-- SagaCommandHandlerRegistrar.java   BeanPostProcessor: scans + registers listeners
|   |-- SagaCommandListenerAdapter.java    Kafka message handler: deser, dispatch, classify, reply
|   |-- SagaSdkAutoConfiguration.java     Auto-config entry point
|
|-- exception/
|   |-- SagaBusinessException.java     Throw for business failures (-> compensate)
|   |-- SagaTechnicalException.java    Throw for technical failures (-> retry/suspend)
|
|-- idempotency/
|   |-- ProcessedCommand.java          MongoDB document: cmdKey, replyJson, timestamp
|   |-- ProcessedCommandRepository.java
|
|-- validation/
|   |-- ParticipantValidator.java      Startup cross-check against orchestrator YAML
```

### saga-orchestrator-example (6 source files)

```
com.anirudh.saga.example/
|-- ExampleApplication.java
|-- order/OrderController.java
|-- inventory/InventoryCommandHandler.java     @SagaParticipant(module="inventory")
|-- payment/PaymentController.java
|-- shipment/ShipmentCommandHandler.java       @SagaParticipant(module="shipment")
resources/
|-- sagas/order-placement-saga.yml             4-step saga definition
```

### Unit Tests (8 test classes)

| Test Class | What It Covers |
|---|---|
| `SagaOrchestratorTest` | 16 tests: full success, business failure, technical failure, HTTP inline, compensation failure, idempotency, edge cases, lock integration |
| `SagaStateMachineTest` | 15 tests: all state transitions, step completion, compensation flow, validation guards, metrics recording |
| `IdempotencyGuardTest` | SHA-256 computation, deterministic key ordering, DuplicateKeyException handling, findExisting |
| `KafkaStepExecutorTest` | Outbox write verification, serialization, compensation dispatch |
| `HttpStepExecutorTest` | Success/failure/exception paths, RestClient interaction, compensation URL |
| `OutboxPollerTest` | Poll threshold logic, header attachment, status update, empty poll |
| `SagaDefinitionValidatorTest` | Valid YAML, missing fields, invalid step types, unknown modules |
| `SagaLockManagerTest` | Acquire, release, expired lock replacement, duplicate lock detection, cleanup |

---

## 9. Docker and Infrastructure

### Docker Compose Setup

```
docker-compose up -d
```

| Service | Image | Host Port | Container Port | Purpose |
|---|---|---|---|---|
| `saga-mongodb` | mongo:7.0 | 27117 | 27017 | MongoDB single-node ReplicaSet |
| `saga-zookeeper` | cp-zookeeper:7.5.0 | 2281 | 2181 | Kafka coordination |
| `saga-kafka` | cp-kafka:7.5.0 | 9192 | 9092 | Event bus |
| `saga-debezium` | debezium/connect:2.4 | 8183 | 8083 | CDC connector |

### MongoDB Single-Node ReplicaSet

MongoDB is started with `--replSet rs0` and an inline entrypoint script that:

1. Starts `mongod` in background with `--replSet rs0 --bind_ip_all`
2. Waits for mongod readiness via `db.adminCommand('ping')`
3. Initiates single-node ReplicaSet: `rs.initiate({ _id: "rs0", members: [{ _id: 0, host: "saga-mongodb:27017" }] })`
4. Waits for PRIMARY state
5. Creates root user

Healthcheck: `mongosh --quiet --eval "rs.status().ok"` every 10 seconds.

**Why single-node ReplicaSet instead of standalone:** MongoDB requires a ReplicaSet for multi-document transactions and Change Streams. Even in development, you must run a ReplicaSet to use `@Transactional` with Spring Data MongoDB.

### Kafka Dual-Listener Configuration

Kafka is configured with two listeners:

| Listener | Address | Purpose |
|---|---|---|
| INTERNAL | `kafka:9093` | Inter-container communication (Debezium, app containers) |
| EXTERNAL | `localhost:9192` | Host machine access (IDE, tests running outside Docker) |

`KAFKA_AUTO_CREATE_TOPICS_ENABLE: true` for development convenience.

### Debezium CDC Connector

Register after `docker-compose up`:

```bash
curl -X POST http://localhost:8183/connectors \
  -H "Content-Type: application/json" \
  -d @docker/debezium/register-connector.json
```

Connector configuration:
```json
{
  "name": "saga-outbox-connector",
  "config": {
    "connector.class": "io.debezium.connector.mongodb.MongoDbConnector",
    "mongodb.connection.string": "mongodb://root:root@saga-mongodb:27017/?replicaSet=rs0&authSource=admin",
    "collection.include.list": "saga.saga_outbox",
    "transforms": "outbox",
    "transforms.outbox.type": "io.debezium.transforms.outbox.EventRouter",
    "transforms.outbox.route.topic.replacement": "${routedByValue}"
  }
}
```

The `EventRouter` transform reads the `eventType` field from each outbox document and routes to the appropriate Kafka topic (e.g., `inventory-commands`).

---

## 10. v2 Roadmap

### Parallel Step Execution

**Current:** Steps execute strictly sequentially (step 1 -> step 2 -> step 3).

**v2:** Allow steps to execute in parallel when they have no dependencies.

```yaml
steps:
  - name: reserve-inventory
    type: KAFKA
    ...
  - name: authorize-payment
    type: HTTP
    ...
    parallel: true   # runs concurrently with reserve-inventory
  - name: schedule-shipment
    type: KAFKA
    ...
    waitFor: [reserve-inventory, authorize-payment]  # barrier
```

**Impact:** Requires changing `currentStep` from a single integer to a set of completed step names. Compensation ordering becomes: reverse of completion order within each parallel group, then reverse of group order.

### Conditional Branching

**Current:** All steps in a saga always execute.

**v2:** Allow steps to be skipped based on payload or context values.

```yaml
steps:
  - name: check-fraud
    type: HTTP
    ...
  - name: enhanced-verification
    type: HTTP
    ...
    condition: "context['check-fraud'].riskScore > 70"
```

### Sub-Workflows (Nested Sagas)

**v2:** A saga step can trigger another saga. The parent saga waits for the child saga to complete.

```yaml
steps:
  - name: process-payment
    type: SAGA
    sagaType: payment-processing-saga
    payload:
      amount: "${payload.totalAmount}"
```

### Confirmed Kafka Compensation

**Current:** Kafka compensations are fire-and-forget.

**v2:** Compensation steps wait for a reply, just like forward steps. If compensation fails, retry with backoff before marking as FAILED.

### Dynamic Saga Definitions from MongoDB

**v2:** Store saga definitions in `saga_definitions` collection instead of classpath YAML. Enables runtime updates without redeployment.

### Versioned Saga Definitions

**v2:** Saga definitions have version numbers. In-flight sagas use the version they started with. New triggers use the latest ACTIVE version. DEPRECATED versions cannot start new sagas but in-flight ones continue to completion.

---

## 11. Prometheus Metrics

All metrics are registered via Micrometer and exposed through Spring Boot Actuator at `/actuator/prometheus`.

### Counters

| Metric Name | Description | Incremented When |
|---|---|---|
| `saga.started` | Total sagas started | `SagaStateMachine.initialize()` |
| `saga.completed` | Total sagas completed successfully | Last step completes, status -> COMPLETED |
| `saga.compensated` | Total sagas compensated | All compensation steps succeed, status -> COMPENSATED |
| `saga.failed` | Total sagas failed (compensation failure) | At least one compensation step failed, status -> FAILED |
| `saga.suspended` | Total sagas suspended (technical failure) | Technical failure after retries exhausted, status -> SUSPENDED |
| `saga.step.executed` | Total steps executed (forward) | `SagaStateMachine.startStep()` |
| `saga.step.failed` | Total steps failed | Compensation step fails |

### Timers

| Metric Name | Description | Usage |
|---|---|---|
| `saga.duration` | Saga execution duration (start to terminal state) | `SagaMetrics.startTimer()` / `stopTimer()` |

### Monitoring Queries (PromQL)

```promql
# Saga success rate (last 5 minutes)
rate(saga_completed_total[5m]) / rate(saga_started_total[5m])

# Current suspended sagas requiring attention
saga_suspended_total - saga_started_total + saga_completed_total + saga_compensated_total + saga_failed_total

# Step failure rate
rate(saga_step_failed_total[5m]) / rate(saga_step_executed_total[5m])

# Average saga duration (p99)
histogram_quantile(0.99, rate(saga_duration_seconds_bucket[5m]))
```

---

## 12. API Reference

All endpoints are auto-configured in `saga-orchestrator-core`. No setup required.

Base path: `/sagas`

### Standard Response Wrapper

All responses use this format:

```json
// Success
{ "success": true, "data": { ... }, "error": null }

// Error
{ "success": false, "data": null, "error": { "code": "SAGA_NOT_FOUND", "message": "..." } }
```

---

### POST /sagas -- Start a Saga

**Request:**
```json
{
  "sagaType": "order-placement-saga",
  "payload": {
    "orderId": "ORD-12345",
    "productId": "PROD-001",
    "quantity": 2,
    "customerId": "CUST-789"
  },
  "idempotencyKey": "optional-custom-key"
}
```

`idempotencyKey` is optional. If omitted, the engine computes SHA-256 of `sagaType + sorted payload`.

**Response (200):**
```json
{
  "success": true,
  "data": {
    "sagaId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "sagaType": "order-placement-saga",
    "status": "STARTED",
    "currentStep": 0,
    "payload": { "orderId": "ORD-12345", ... },
    "context": {},
    "createdAt": "2026-03-26T10:00:00Z",
    "timeoutAt": "2026-03-26T10:30:00Z",
    "version": 0
  },
  "error": null
}
```

**Error codes:** `SAGA_DEFINITION_NOT_FOUND` (404), `SAGA_LOCK_HELD` (409)

---

### GET /sagas/{sagaId} -- Full Execution Timeline

**Response (200):**
```json
{
  "success": true,
  "data": {
    "saga": {
      "sagaId": "a1b2c3d4-...",
      "sagaType": "order-placement-saga",
      "status": "COMPLETED",
      "currentStep": 4,
      "payload": { ... },
      "context": {
        "reserve-inventory": { "reservationId": "RES-a1b2c3d4" },
        "charge-payment": { "transactionId": "TXN-98765" }
      }
    },
    "timeline": [
      { "sagaId": "a1b2c3d4-...", "stepName": "SAGA", "event": "STARTED", "timestamp": "..." },
      { "sagaId": "a1b2c3d4-...", "stepName": "reserve-inventory", "event": "STEP_STARTED", "timestamp": "..." },
      { "sagaId": "a1b2c3d4-...", "stepName": "reserve-inventory", "event": "STEP_COMPLETED", "timestamp": "..." },
      { "sagaId": "a1b2c3d4-...", "stepName": "charge-payment", "event": "STEP_STARTED", "timestamp": "..." },
      { "sagaId": "a1b2c3d4-...", "stepName": "charge-payment", "event": "STEP_COMPLETED", "timestamp": "..." },
      { "sagaId": "a1b2c3d4-...", "stepName": "SAGA", "event": "COMPLETED", "timestamp": "..." }
    ]
  },
  "error": null
}
```

**Error codes:** `SAGA_NOT_FOUND` (404)

---

### GET /sagas?status={status} -- List Sagas by Status

**Valid status values:** `STARTED`, `IN_PROGRESS`, `COMPLETED`, `COMPENSATING`, `COMPENSATED`, `SUSPENDED`, `FAILED`

**Example:** `GET /sagas?status=SUSPENDED`

**Response (200):**
```json
{
  "success": true,
  "data": [
    { "sagaId": "...", "sagaType": "...", "status": "SUSPENDED", ... },
    { "sagaId": "...", "sagaType": "...", "status": "SUSPENDED", ... }
  ],
  "error": null
}
```

Omit `status` parameter to list all sagas.

---

### POST /sagas/{sagaId}/retry -- Retry a Suspended Saga

Resumes a SUSPENDED saga from its current step.

**Response (200):**
```json
{ "success": true, "data": "Saga retry triggered", "error": null }
```

**Error codes:** `SAGA_NOT_FOUND` (404), `SAGA_INVALID_STATE` (400, if saga is not SUSPENDED)

---

### POST /sagas/{sagaId}/compensate -- Manually Trigger Compensation

Forces compensation on a saga that is IN_PROGRESS or SUSPENDED.

**Response (200):**
```json
{ "success": true, "data": "Saga compensation triggered", "error": null }
```

**Error codes:** `SAGA_NOT_FOUND` (404), `SAGA_INVALID_STATE` (400, if saga is COMPLETED/COMPENSATED/FAILED)

---

### GET /saga-definitions -- List All Loaded Saga Definitions

Used by SDK `ParticipantValidator` for startup cross-check. Also useful for debugging.

**Response (200):**
```json
{
  "success": true,
  "data": {
    "order-placement-saga": {
      "name": "order-placement-saga",
      "timeoutMinutes": 30,
      "steps": [
        { "name": "reserve-inventory", "type": "KAFKA", "module": "inventory", "action": "RESERVE_INVENTORY", ... },
        { "name": "charge-payment", "type": "HTTP", "action": "CHARGE_PAYMENT", "url": "http://...", ... }
      ],
      "lockTargetType": "order",
      "lockTargetField": "orderId"
    }
  },
  "error": null
}
```

---

### Error Code Reference

| Code | HTTP Status | Cause |
|---|---|---|
| `SAGA_NOT_FOUND` | 404 | sagaId does not exist |
| `SAGA_DEFINITION_NOT_FOUND` | 404 | sagaType not in loaded YAML definitions |
| `SAGA_INVALID_STATE` | 400 | Operation not valid for current saga status |
| `SAGA_ALREADY_EXISTS` | 409 | Idempotency key conflict |
| `SAGA_LOCK_HELD` | 409 | Another saga holds the aggregate lock |
| `SAGA_EXECUTION_ERROR` | 500 | Internal engine error |
| `INTERNAL_ERROR` | 500 | Unexpected error |

---

## 13. YAML Schema Reference

Saga definitions are placed in `src/main/resources/sagas/` and loaded at startup.

### Full Schema

```yaml
# Required: unique saga type name
name: order-placement-saga

# Required: timeout in minutes for the entire saga
# If no reply is received within this time, the saga is compensated
timeoutMinutes: 30

# Optional: aggregate locking
# Prevents concurrent sagas from operating on the same business entity
lockTargetType: order              # entity type (used as lock key prefix)
lockTargetField: orderId           # field name in payload to extract entity ID

# Required: ordered list of steps
steps:
  # ── Kafka Step ──────────────────────────────────────────────────
  - name: reserve-inventory        # unique step name within this saga
    type: KAFKA                    # KAFKA or HTTP
    module: inventory              # derives topic: {module}-commands
    # topic: custom-topic          # explicit override (takes precedence over module)
    action: RESERVE_INVENTORY      # SCREAMING_SNAKE_CASE, routed to @SagaCommandHandler
    compensationAction: RELEASE_INVENTORY  # optional: compensation action name
    # compensationTopic: custom-comp-topic # optional: defaults to same topic as forward
    retryMaxAttempts: 3            # Spring Kafka retry attempts before DLT
    timeoutSeconds: 30             # per-step timeout (not yet enforced in v1)

  # ── HTTP Step ───────────────────────────────────────────────────
  - name: charge-payment
    type: HTTP
    action: CHARGE_PAYMENT
    url: http://localhost:8080/payment/charge              # forward step URL
    compensationAction: REFUND_PAYMENT
    compensationUrl: http://localhost:8080/payment/refund   # compensation URL
    retryMaxAttempts: 2
    timeoutSeconds: 10

  # ── Step without compensation (read-only or final) ──────────────
  - name: confirm-order
    type: KAFKA
    module: order
    action: CONFIRM_ORDER
    retryMaxAttempts: 3
    timeoutSeconds: 30
    # No compensationAction = step is skipped during compensation
```

### Field Reference

| Field | Required | Applies To | Description |
|---|---|---|---|
| `name` (saga) | Yes | Saga | Unique identifier. Used as `sagaType` in API. |
| `timeoutMinutes` | Yes | Saga | Minutes before entire saga times out. |
| `lockTargetType` | No | Saga | Entity type for aggregate locking. |
| `lockTargetField` | No | Saga | Payload field name containing the entity ID. |
| `name` (step) | Yes | Step | Unique within saga. Used as `stepId` in messages. |
| `type` | Yes | Step | `KAFKA` or `HTTP`. |
| `module` | No* | KAFKA step | Derives topic as `{module}-commands`. |
| `topic` | No* | KAFKA step | Explicit topic override. |
| `url` | Yes** | HTTP step | URL for the forward step POST request. |
| `action` | Yes | Step | Action name for routing (SCREAMING_SNAKE_CASE). |
| `compensationAction` | No | Step | Action name for compensation. Omit for no compensation. |
| `compensationTopic` | No | KAFKA step | Explicit compensation topic. Defaults to forward topic. |
| `compensationUrl` | No*** | HTTP step | URL for compensation POST. Required if `compensationAction` is set. |
| `retryMaxAttempts` | No | Step | Max retry attempts. Default varies by type. |
| `timeoutSeconds` | No | Step | Per-step timeout hint (reserved for v2 enforcement). |

\* For KAFKA steps, either `module` or `topic` must be provided.
\** Required for HTTP steps.
\*** Required for HTTP steps that have `compensationAction`.

### Topic Resolution Order

For KAFKA steps, the topic is resolved in this order:
1. Explicit `topic` field (if set)
2. `{module}-commands` (if `module` is set)
3. Error at startup validation

Same logic applies to `compensationTopic` / compensation module.

### Example: Order Placement Saga

```yaml
name: order-placement-saga
timeoutMinutes: 30
lockTargetType: order
lockTargetField: orderId
steps:
  - name: reserve-inventory
    type: KAFKA
    module: inventory
    action: RESERVE_INVENTORY
    compensationAction: RELEASE_INVENTORY
    retryMaxAttempts: 3
    timeoutSeconds: 30

  - name: charge-payment
    type: HTTP
    action: CHARGE_PAYMENT
    url: http://localhost:8080/payment/charge
    compensationAction: REFUND_PAYMENT
    compensationUrl: http://localhost:8080/payment/refund
    retryMaxAttempts: 2
    timeoutSeconds: 10

  - name: schedule-shipment
    type: KAFKA
    module: shipment
    action: SCHEDULE_SHIPMENT
    compensationAction: CANCEL_SHIPMENT
    retryMaxAttempts: 3
    timeoutSeconds: 30

  - name: confirm-order
    type: KAFKA
    module: order
    action: CONFIRM_ORDER
    retryMaxAttempts: 3
    timeoutSeconds: 30
```

**Flow:** reserve-inventory (Kafka) -> charge-payment (HTTP, inline) -> schedule-shipment (Kafka) -> confirm-order (Kafka, no compensation)

**On failure at charge-payment:** compensate reserve-inventory (RELEASE_INVENTORY via Kafka)

**On failure at confirm-order:** compensate schedule-shipment (CANCEL_SHIPMENT), then charge-payment (REFUND_PAYMENT via HTTP), then reserve-inventory (RELEASE_INVENTORY via Kafka)

---

## Deployment Constraint

**Do NOT change step names, step order, or compensation references in a saga YAML while instances of that saga type are IN_PROGRESS.** Running instances hold references to the definition by name -- changes will corrupt in-flight sagas.

**Safe procedure:** Drain all in-flight sagas of the affected type before deploying a new definition version. v2 will introduce formal definition versioning to remove this constraint.

---

## CI/CD Pipeline

GitHub Actions workflow (`.github/workflows/ci.yml`):

| Trigger | Action |
|---|---|
| Push to `main` or `develop` | Build + Test |
| Pull request to `main` | Build + Test |
| GitHub Release created | Build + Test + Publish to GitHub Packages |

Steps: Checkout -> Java 17 (Temurin) -> Gradle cache -> `./gradlew build test` -> JUnit test report -> (on release) `./gradlew publish`

---

## Connection Strings (Local Development)

```properties
# MongoDB
spring.data.mongodb.uri=mongodb://root:root@localhost:27117/saga?authSource=admin&replicaSet=rs0

# Kafka
spring.kafka.bootstrap-servers=localhost:9192

# Debezium REST API
http://localhost:8183/connectors
```
