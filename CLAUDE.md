# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Build
mvn package

# Run (requires infrastructure)
mvn spring-boot:run

# Run tests
mvn test

# Run a single test class
mvn test -Dtest=MyTestClass

# Run a single test method
mvn test -Dtest=MyTestClass#myMethod

# Skip tests during build
mvn package -DskipTests

# Docker build
docker build -t plantogether-task-service .
```

## Architecture

This is a Spring Boot 3.3.6 microservice (Java 21) within the PlanTogether platform. It manages collaborative trip task lists.

**Package:** `com.plantogether.task`

**Port:** 8085 (local), 8080 (Docker container)

### Infrastructure dependencies

- **PostgreSQL** (`plantogether_task` DB) — task persistence
- **RabbitMQ** — publishes task events; consumes `TripCreated` and `MemberJoined`
- **Redis** — caches active tasks
- **Keycloak** (realm: `plantogether`) — JWT auth via OIDC
- **Eureka** — service discovery

### Key design patterns

**Security:** Stateless JWT via `KeycloakJwtConverter`, which extracts `realm_access.roles` from the Keycloak token and maps them to `ROLE_<ROLENAME>` Spring authorities. The principal name is set to `jwt.getSubject()` (Keycloak user UUID). Only `/actuator/health` and `/actuator/info` are public; everything else requires authentication.

**Authorization rules:** Only task creator or trip organizer can edit; only assignee or organizer can change status. Zero PII stored — only Keycloak UUIDs.

**Database migrations:** Flyway is used (`ddl-auto: validate`). Migration files go in `src/main/resources/db/migration/` following the `V{n}__{description}.sql` naming convention. The initial schema (`V1__init_schema.sql`) is currently empty — tables for `Task`, `Subtask`, and `TaskReminder` need to be created.

**Exception handling:** `GlobalExceptionHandler` uses `plantogether-common` library types (`ResourceNotFoundException`, `AccessDeniedException`, `ErrorResponse`).

**Events (RabbitMQ):**
- Publishes: `TaskCreated`, `TaskAssigned`, `TaskStatusChanged`, `TaskCompleted`, `DeadlineReminder`, `DeadlineReached`
- Consumes: `TripCreated`, `MemberJoined`

**Scheduled jobs:** A reminder job runs hourly (`scheduler.reminders.interval: PT1H`) to publish `DeadlineReminder` events 24h before deadlines.

### Domain model

- `Task` — UUID id, trip_id, title, description, deadline, assigned_to (Keycloak UUID), priority (HIGH/MEDIUM/LOW), status (TODO/IN_PROGRESS/DONE), created_by, progress (0-100), timestamps
- `Subtask` — UUID id, task_id (FK), title, status, assigned_to, timestamps
- `TaskReminder` — UUID id, task_id (FK), reminder_type (DEADLINE_24H/DEADLINE_1H/DEADLINE_REACHED), triggered_at, next_trigger_at

Progress calculation: if subtasks exist, progress = (DONE subtasks / total subtasks) × 100; otherwise manually set.

### Common library

The `plantogether-common` (v1.0.0-SNAPSHOT) dependency provides shared exception types and `ErrorResponse`. It must be available in the local Maven repository.
