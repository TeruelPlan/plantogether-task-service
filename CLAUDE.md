# CLAUDE.md

This file provides guidance to Claude when working with code in this repository.

## Commands

```bash
# Build
mvn clean package -DskipTests

# Build with tests
mvn clean package

# Run locally
mvn spring-boot:run

# Run all tests
mvn test

# Run a single test class
mvn test -Dtest=MyTestClass

# Run a single test method
mvn test -Dtest=MyTestClass#myMethod

# Docker build
docker build -t plantogether-task-service .
```

**Prerequisites:** install shared libs first:
```bash
cd ../plantogether-proto && mvn clean install
cd ../plantogether-common && mvn clean install
```

## Architecture

Spring Boot 3.3.6 microservice (Java 21). Manages trip task lists, assignments, priorities, deadlines, and subtasks.

**Ports:** REST `8085` · gRPC `9085` (server — reserved for future consumers)

**Package:** `com.plantogether.task`

### Package structure

```
com.plantogether.task/
├── config/          # SecurityConfig, RabbitConfig, SchedulerConfig
├── controller/      # REST controllers
├── domain/          # JPA entities (Task)
├── repository/      # Spring Data JPA
├── service/         # Business logic + DeadlineReminderScheduler
├── dto/             # Request/Response DTOs (Lombok @Data @Builder)
├── grpc/
│   └── client/      # TripGrpcClient (CheckMembership → trip-service:9081)
└── event/
    └── publisher/   # RabbitMQ publishers (TaskAssigned, TaskDeadlineReminder)
```

### Infrastructure dependencies

| Dependency | Default (local) | Purpose |
|---|---|---|
| PostgreSQL 16 | `localhost:5432/plantogether_task` | Primary persistence (db_task) |
| RabbitMQ | `localhost:5672` | Event publishing |
| Redis | `localhost:6379` | Caching active tasks |
| Keycloak 24+ | `localhost:8180` realm `plantogether` | JWT validation via JWKS |
| trip-service gRPC | `localhost:9081` | CheckMembership before every write |


### Domain model (db_task)

**`task`** — single table for both tasks and subtasks (self-referential FK):

| Column | Type | Notes |
|---|---|---|
| id | UUID | PK |
| trip_id | UUID | NOT NULL |
| parent_task_id | UUID | FK → task.id (nullable — 1 level max, no deep nesting) |
| title | VARCHAR(255) | NOT NULL |
| description | TEXT | NULLABLE |
| assignee_id | UUID | Keycloak UUID of assignee (nullable) |
| status | ENUM | `TODO` / `IN_PROGRESS` / `DONE` |
| priority | ENUM | `HIGH` / `MEDIUM` / `LOW` |
| deadline | TIMESTAMP | NULLABLE |
| created_by | UUID | Keycloak UUID |
| completed_at | TIMESTAMP | NULLABLE — set when status → DONE |
| created_at | TIMESTAMP | NOT NULL |
| updated_at | TIMESTAMP | NOT NULL |

Subtasks: rows where `parent_task_id IS NOT NULL`. Maximum 1 level of nesting (subtasks cannot have subtasks).

### gRPC client

Calls `TripGrpcService.CheckMembership(tripId, userId)` on trip-service:9081 before every write operation.

### REST API (`/api/v1/`)

| Method | Endpoint | Auth | Notes |
|---|---|---|---|
| POST | `/api/v1/trips/{tripId}/tasks` | Bearer JWT + member | Create task or subtask |
| GET | `/api/v1/trips/{tripId}/tasks` | Bearer JWT + member | List (filterable: `?status=TODO&assignee=uuid&priority=HIGH`) |
| PUT | `/api/v1/tasks/{id}` | Bearer JWT + creator or ORGANIZER | Modify task |
| PATCH | `/api/v1/tasks/{id}/status` | Bearer JWT + assignee or ORGANIZER | Change status |
| DELETE | `/api/v1/tasks/{id}` | Bearer JWT + creator or ORGANIZER | Delete task |

### Scheduler

`DeadlineReminderScheduler` runs on a configurable interval (e.g. hourly) to scan for tasks with deadlines within
the next 24h and publish `task.deadline.reminder` events for notification-service.

### RabbitMQ events

**Publishes** (exchange `plantogether.events`):
- `task.assigned` — routing key `task.assigned` — when `assignee_id` is set or changed
- `task.deadline.reminder` — routing key `task.deadline.reminder` — emitted by scheduler for upcoming deadlines

This service does **not** consume any events.

### Security

- Stateless JWT via `KeycloakJwtConverter` — `realm_access.roles` → `ROLE_<ROLE>` Spring authorities
- Principal name = Keycloak subject UUID
- Public endpoints: `/actuator/health`, `/actuator/info`
- Authorization: only task creator or ORGANIZER can edit/delete; only assignee or ORGANIZER can change status
- Zero PII stored — only Keycloak UUIDs

### Environment variables

| Variable | Default |
|---|---|
| `DB_HOST` | `localhost` |
| `DB_USER` | `plantogether` |
| `DB_PASSWORD` | `plantogether` |
| `RABBITMQ_HOST` | `localhost` |
| `RABBITMQ_PORT` | `5672` |
| `REDIS_HOST` | `localhost` |
| `KEYCLOAK_URL` | `http://localhost:8180` |
| `TRIP_SERVICE_GRPC_HOST` | `localhost` |
| `TRIP_SERVICE_GRPC_PORT` | `9081` |

