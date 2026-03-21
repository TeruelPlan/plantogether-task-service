# Task Service

> Service de gestion collaborative des tâches de voyage

## Rôle dans l'architecture

Le Task Service gère la liste des tâches à accomplir avant et pendant le voyage. Il supporte les sous-tâches
(un niveau de profondeur max), les assignations, les priorités et les deadlines. Un scheduler interne publie
des rappels de deadline via RabbitMQ. L'appartenance au trip est vérifiée via gRPC avant chaque opération.

## Fonctionnalités

- Création de tâches et sous-tâches (1 niveau max)
- Assignation à un membre du trip
- Priorités : HIGH / MEDIUM / LOW
- Statuts : TODO / IN_PROGRESS / DONE
- Deadlines avec rappels automatiques (scheduler)
- Vérification d'appartenance via gRPC (TripService.CheckMembership)

## Endpoints REST

| Méthode | Endpoint | Description |
|---------|----------|-------------|
| POST | `/api/v1/trips/{id}/tasks` | Créer une tâche |
| GET | `/api/v1/trips/{id}/tasks` | Liste (filtrable par status / assignee / priority) |
| PUT | `/api/v1/tasks/{id}` | Modifier une tâche |
| PATCH | `/api/v1/tasks/{id}/status` | Changer le statut uniquement |
| DELETE | `/api/v1/tasks/{id}` | Supprimer une tâche |

## gRPC Client

- `TripService.CheckMembership(tripId, userId)` — vérification d'appartenance avant chaque opération

## Modèle de données (`db_task`)

**task**

| Colonne | Type | Description |
|---------|------|-------------|
| `id` | UUID PK | Identifiant unique (UUID v7) |
| `trip_id` | UUID NOT NULL | Référence au trip |
| `parent_task_id` | UUID NULLABLE FK→task | Référence à la tâche parente (1 niveau max) |
| `title` | VARCHAR(255) NOT NULL | Titre de la tâche |
| `description` | TEXT NULLABLE | Description |
| `assignee_id` | UUID NULLABLE | keycloak_id du membre assigné |
| `status` | ENUM NOT NULL | TODO / IN_PROGRESS / DONE |
| `priority` | ENUM NOT NULL | HIGH / MEDIUM / LOW |
| `deadline` | TIMESTAMP NULLABLE | Date limite |
| `created_by` | UUID NOT NULL | keycloak_id du créateur |
| `completed_at` | TIMESTAMP NULLABLE | Date de complétion |
| `created_at` | TIMESTAMP NOT NULL | |
| `updated_at` | TIMESTAMP NOT NULL | |

## Événements RabbitMQ (Exchange : `plantogether.events`)

**Publie :**

| Routing Key | Déclencheur |
|-------------|-------------|
| `task.assigned` | Tâche assignée à un membre |
| `task.deadline.reminder` | Rappel automatique (scheduler, avant deadline) |

**Consomme :** aucun

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

## Lancer en local

```bash
# Prérequis : docker compose --profile essential up -d
# + plantogether-proto et plantogether-common installés

mvn spring-boot:run
```

## Dépendances

- **Keycloak 24+** : validation JWT
- **PostgreSQL 16** (`db_task`) : tâches et sous-tâches
- **RabbitMQ** : publication d'événements (`task.assigned`, `task.deadline.reminder`)
- **Redis** : rate limiting (Bucket4j)
- **Trip Service** (gRPC 9081) : vérification d'appartenance au trip
- **plantogether-proto** : contrats gRPC (client + serveur)
- **plantogether-common** : DTOs events, CorsConfig

## Sécurité

- Tous les endpoints requièrent un token Bearer Keycloak valide
- L'appartenance au trip est vérifiée via gRPC avant chaque opération
- Seul le créateur ou l'ORGANIZER peut supprimer une tâche
- Zero PII stockée (uniquement des `keycloak_id`)
