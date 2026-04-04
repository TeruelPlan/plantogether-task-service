# Task Service

> Collaborative trip task management service

## Role in the Architecture

The Task Service manages the to-do list for trips. It supports subtasks (one level deep max),
assignments, priorities, and deadlines. An internal scheduler publishes deadline reminders via RabbitMQ.
Trip membership is verified via gRPC before each operation.

## Features

- Task and subtask creation (1 level max)
- Assignment to a trip member
- Priorities: HIGH / MEDIUM / LOW
- Statuses: TODO / IN_PROGRESS / DONE
- Deadlines with automatic reminders (scheduler)
- Membership verification via gRPC (TripService.IsMember)

## REST Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/trips/{id}/tasks` | Create a task |
| GET | `/api/v1/trips/{id}/tasks` | List (filterable by status / assignee / priority) |
| PUT | `/api/v1/tasks/{id}` | Update a task |
| PATCH | `/api/v1/tasks/{id}/status` | Change status only |
| DELETE | `/api/v1/tasks/{id}` | Delete a task |

## gRPC Client

- `TripService.IsMember(tripId, deviceId)` — membership verification before each operation

## Data Model (`db_task`)

**task**

| Column | Type | Description |
|--------|------|-------------|
| `id` | UUID PK | Unique identifier (UUID v7) |
| `trip_id` | UUID NOT NULL | Trip reference |
| `parent_task_id` | UUID NULLABLE FK→task | Parent task reference (1 level max) |
| `title` | VARCHAR(255) NOT NULL | Task title |
| `description` | TEXT NULLABLE | Description |
| `assignee_id` | UUID NULLABLE | device_id of the assigned member |
| `status` | ENUM NOT NULL | TODO / IN_PROGRESS / DONE |
| `priority` | ENUM NOT NULL | HIGH / MEDIUM / LOW |
| `deadline` | TIMESTAMP NULLABLE | Due date |
| `created_by` | UUID NOT NULL | device_id of the creator |
| `completed_at` | TIMESTAMP NULLABLE | Completion date |
| `created_at` | TIMESTAMP NOT NULL | |
| `updated_at` | TIMESTAMP NOT NULL | |

## RabbitMQ Events (Exchange: `plantogether.events`)

**Publishes:**

| Routing Key | Trigger |
|-------------|---------|
| `task.assigned` | Task assigned to a member |
| `task.deadline.reminder` | Automatic reminder (scheduler, before deadline) |

**Consumes:** none

## Configuration

```yaml
server:
  port: 8085

spring:
  application:
    name: plantogether-task-service
  datasource:
    url: jdbc:postgresql://postgres:5432/db_task
    username: ${DB_USER}
    password: ${DB_PASSWORD}
  jpa:
    hibernate:
      ddl-auto: validate

grpc:
  client:
    trip-service:
      address: static://trip-service:9081
  server:
    port: 9085
```

## Running Locally

```bash
# Prerequisites: docker compose up -d
# + plantogether-proto and plantogether-common installed

mvn spring-boot:run
```

## Dependencies

- **PostgreSQL 16** (`db_task`): tasks and subtasks
- **RabbitMQ**: event publishing (`task.assigned`, `task.deadline.reminder`)
- **Redis**: rate limiting (Bucket4j)
- **Trip Service** (gRPC 9081): trip membership verification
- **plantogether-proto**: gRPC contracts (client + server)
- **plantogether-common**: event DTOs, DeviceIdFilter, SecurityAutoConfiguration, CorsConfig

## Security

- Anonymous device-based identity: `X-Device-Id` header on every request
- `DeviceIdFilter` (from plantogether-common, auto-configured via `SecurityAutoConfiguration`) extracts the device UUID and sets the SecurityContext principal
- No JWT, no Keycloak, no login, no sessions
- Trip membership is verified via gRPC before each operation
- Only the creator or ORGANIZER can delete a task
- Zero PII stored (only `device_id` references)
