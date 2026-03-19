# Task Service

> Service de gestion collaborative de la liste de tâches

## Rôle dans l'architecture

Le Task Service gère la liste collaborative de tâches (to-do list) du voyage. Les participants créent, modifient et
terminent des tâches avec deadlines, priorités et assignations. Le service envoie automatiquement des rappels 24h avant
la deadline, supporte les sous-tâches (1 niveau), et publie des événements pour que les autres services (notamment
notifications) puissent alerter les utilisateurs.

## Fonctionnalités

- Création et gestion des tâches avec titre, description et deadline
- Assignation des tâches à des participants
- Hiérarchie de tâches (support des sous-tâches, 1 niveau)
- Priorités : HAUTE, MOYENNE, BASSE
- Statuts : À_FAIRE, EN_COURS, TERMINÉE
- Rappels automatiques 24h avant la deadline
- Historique des modifications
- Attributs de suivi : avancement, date de création, date de modification
- Publication d'événements pour les notifications

## Endpoints REST

| Méthode | Endpoint                       | Description               |
|---------|--------------------------------|---------------------------|
| POST    | `/api/trips/{tripId}/tasks`    | Créer une tâche           |
| GET     | `/api/trips/{tripId}/tasks`    | Lister les tâches         |
| GET     | `/api/tasks/{taskId}`          | Récupérer une tâche       |
| PUT     | `/api/tasks/{taskId}`          | Modifier une tâche        |
| DELETE  | `/api/tasks/{taskId}`          | Supprimer une tâche       |
| PUT     | `/api/tasks/{taskId}/status`   | Changer le statut         |
| POST    | `/api/tasks/{taskId}/assign`   | Assigner à un participant |
| POST    | `/api/tasks/{taskId}/subtasks` | Créer une sous-tâche      |
| GET     | `/api/tasks/{taskId}/subtasks` | Lister les sous-tâches    |
| PUT     | `/api/tasks/{subtaskId}`       | Modifier une sous-tâche   |
| DELETE  | `/api/tasks/{subtaskId}`       | Supprimer une sous-tâche  |

## Modèle de données

**Task**

- `id` (UUID) : identifiant unique
- `trip_id` (UUID, FK) : voyage associé
- `title` (String) : titre de la tâche
- `description` (String, nullable) : description détaillée
- `deadline` (LocalDateTime, nullable) : date limite
- `assigned_to` (UUID, nullable) : ID Keycloak de l'assigné
- `priority` (ENUM: HIGH, MEDIUM, LOW) : priorité
- `status` (ENUM: TODO, IN_PROGRESS, DONE) : statut
- `created_by` (UUID) : ID Keycloak du créateur
- `progress` (Integer, default: 0) : pourcentage d'avancement (0-100)
- `created_at` (Timestamp)
- `updated_at` (Timestamp)
- `completed_at` (Timestamp, nullable)

**Subtask**

- `id` (UUID)
- `task_id` (UUID, FK) : tâche parente
- `title` (String)
- `status` (ENUM: TODO, IN_PROGRESS, DONE)
- `assigned_to` (UUID, nullable)
- `created_at` (Timestamp)
- `updated_at` (Timestamp)

**TaskReminder**

- `id` (UUID)
- `task_id` (UUID, FK)
- `reminder_type` (ENUM: DEADLINE_24H, DEADLINE_1H, DEADLINE_REACHED)
- `triggered_at` (Timestamp, nullable)
- `next_trigger_at` (Timestamp)

## Événements (RabbitMQ)

**Publie :**

- `TaskCreated` — Émis lors de la création d'une tâche
- `TaskAssigned` — Émis lors d'une assignation
- `TaskStatusChanged` — Émis lors du changement de statut
- `TaskCompleted` — Émis quand une tâche passe au statut DONE
- `DeadlineReminder` — Émis 24h avant la deadline
- `DeadlineReached` — Émis quand la deadline est atteinte

**Consomme :**

- `TripCreated` — Pour initialiser les tâches types du voyage
- `MemberJoined` — Pour mettre à jour les assignations possibles

## Configuration

```yaml
server:
  port: 8085
  servlet:
    context-path: /

spring:
  application:
    name: plantogether-task-service
  jpa:
    hibernate:
      ddl-auto: validate
    show-sql: false
  datasource:
    url: jdbc:postgresql://postgres:5432/plantogether_task
    username: ${DB_USER}
    password: ${DB_PASSWORD}
  rabbitmq:
    host: ${RABBITMQ_HOST:rabbitmq}
    port: ${RABBITMQ_PORT:5672}
    username: ${RABBITMQ_USER}
    password: ${RABBITMQ_PASSWORD}
  redis:
    host: ${REDIS_HOST:redis}
    port: ${REDIS_PORT:6379}

keycloak:
  serverUrl: ${KEYCLOAK_SERVER_URL:http://keycloak:8080}
  realm: ${KEYCLOAK_REALM:plantogether}
  clientId: ${KEYCLOAK_CLIENT_ID}

scheduler:
  reminders:
    enabled: true
    interval: PT1H  # Vérifier chaque heure
```

## Lancer en local

```bash
# Prérequis : Docker Compose (infra), Java 21+, Maven 3.9+

# Option 1 : Maven
mvn spring-boot:run

# Option 2 : Docker
docker build -t plantogether-task-service .
docker run -p 8085:8081 \
  -e KEYCLOAK_SERVER_URL=http://host.docker.internal:8080 \
  -e DB_USER=postgres \
  -e DB_PASSWORD=postgres \
  plantogether-task-service
```

## Dépendances

- **Keycloak 24+** : authentification et autorisation
- **PostgreSQL 16** : persistance des tâches
- **RabbitMQ** : publication d'événements
- **Redis** : cache des tâches actives
- **Spring Boot 3.3.6** : framework web
- **Spring Scheduling** : tâches planifiées (rappels)
- **Spring Cloud Netflix Eureka** : service discovery

## Logique métier

### Calcul du pourcentage d'avancement (tâche parente)

```
Si la tâche a des sous-tâches :
  progression = (nombre_sous-tâches_DONE / nombre_total_sous-tâches) × 100
Sinon :
  progression = valeur définie manuellement
```

### Rappels automatiques

Un job planifié s'exécute toutes les heures pour :

1. Trouver les tâches avec deadline dans les 24h
2. Publier un événement `DeadlineReminder`
3. Marquer comme "rappel envoyé" pour ne pas dupliquer

Le service de notifications se charge de l'envoi du message utilisateur.

## Notes de sécurité

- Seul le créateur ou un organisateur peut modifier une tâche
- Seul l'assigné ou un organisateur peut changer le statut
- Tous les endpoints requièrent authentification Keycloak
- Les dates passées sont acceptées pour suivi des tâches historiques
- Zéro PII stockée (seuls les UUIDs Keycloak)

## Bonnes pratiques

- Limiter les tâches à un titre court (<100 caractères)
- Les sous-tâches aident à suivre les tâches complexes
- Les priorités aident à organiser le travail du groupe
- Les deadlines sont affichées en temps local de chaque utilisateur
