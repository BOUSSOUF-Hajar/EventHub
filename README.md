# EventHub

Plateforme de reservation de billets pour evenements (concerts, conferences, sport), construite comme un ensemble de microservices Spring Boot avec un frontend React.

Projet pense pour mettre en pratique — et pouvoir argumenter en entretien — une architecture microservices realiste : authentification centralisee (Keycloak), communication asynchrone (RabbitMQ), Saga avec compensation, et un pipeline CI/CD complet.

> Le detail des exigences fonctionnelles et techniques est dans [`CAHIER_DES_CHARGES.md`](./CAHIER_DES_CHARGES.md).

## Sommaire

- [Architecture](#architecture)
- [Stack technique](#stack-technique)
- [Demarrage rapide](#demarrage-rapide)
- [Structure du projet](#structure-du-projet)
- [Roadmap](#roadmap)
- [Patterns mis en oeuvre](#patterns-mis-en-oeuvre)
- [Tests](#tests)
- [Licence](#licence)

## Architecture

```mermaid
flowchart TD
    Client["Client React"] --> Gateway["API Gateway<br/>routing + JWT"]
    Gateway -. valide les tokens .-> Keycloak["Keycloak<br/>OAuth2 / OIDC"]

    subgraph Services métier
        Events["Service Evenements"]
        Bookings["Service Reservations<br/>Saga"]
        Payments["Service Paiements"]
        Notifications["Service Notifications"]
    end

    Gateway --> Events
    Gateway --> Bookings
    Gateway --> Payments

    Bookings --> Broker["RabbitMQ"]
    Payments --> Broker
    Broker --> Notifications
    Broker --> Bookings
```

Chaque service métier possède sa **propre base de données** (pattern *database per service*) et ne communique avec les autres que via des **événements RabbitMQ** ou via le Gateway pour les appels synchrones depuis le frontend.

## Stack technique

| Couche | Choix |
|---|---|
| Backend | Java 21, Spring Boot 3, Spring Cloud Gateway |
| Sécurité | Keycloak (OAuth2 / OIDC), Spring Security Resource Server |
| Messaging | RabbitMQ |
| Données | PostgreSQL (une instance par service), Redis (verrou de places) |
| Frontend | React 18, TypeScript, Vite, React Query, react-oidc-context |
| Tests | JUnit 5, Mockito, Testcontainers, Vitest |
| CI/CD | GitHub Actions, Docker |

## Demarrage rapide

**Prérequis** : Docker & Docker Compose, Java 21, Maven, Node.js 18+.

```bash
# 1. Lancer toute l'infrastructure (Postgres x3, RabbitMQ, Keycloak, Redis, Mailhog)
docker compose up -d

# 2. Lancer un service backend (exemple : event-service)
cd services/event-service
./mvnw spring-boot:run

# 3. Lancer le frontend
cd frontend
cp .env.examplme .env
npm install
npm run dev
```

Interfaces utiles en local :

| Outil | URL |
|---|---|
| Frontend | http://localhost:5173 |
| Gateway | http://localhost:8080 |
| Keycloak admin | http://localhost:8081 (admin / admin) |
| RabbitMQ management | http://localhost:15672 (guest / guest) |
| Mailhog (emails capturés) | http://localhost:8025 |

Utilisateurs de test (realm `eventhub` déjà importé) :

| Username | Mot de passe | Rôle |
|---|---|---|
| alice.customer | password | CUSTOMER |
| bob.organizer | password | ORGANIZER |

## Structure du projet

```
eventhub/
├── docker-compose.yml          infra locale complete
├── keycloak/realm-export.json  realm pre-configure (roles, client, users de test)
├── services/
│   ├── gateway/                Spring Cloud Gateway + securite JWT
│   ├── event-service/          catalogue d'evenements (CRUD fonctionnel)
│   ├── booking-service/        squelette Saga reservation/paiement
│   ├── payment-service/        squelette paiement mock
│   └── notification-service/   listener RabbitMQ -> email (Mailhog)
└── frontend/                   React + Vite + TypeScript
```

`event-service` est le seul service entierement fonctionnel pour le moment (CRUD + securite) : c'est le point de depart pour comprendre le pattern, avant de completer les autres. Les autres services sont des squelettes compilables avec des `TODO` explicites a l'endroit ou la logique doit etre ajoutee.

## Roadmap

- [x] Infrastructure Docker Compose (Postgres x3, RabbitMQ, Keycloak, Redis, Mailhog)
- [x] Gateway avec validation JWT
- [x] Service Evenements (CRUD + sécurité par rôle)
- [ ] Service Réservations : verrou Redis + table outbox
- [ ] Service Paiements : mock + idempotence
- [ ] Saga orchestrée Réservation → Paiement → Confirmation (+ compensation)
- [ ] Service Notifications : email de confirmation/échec
- [ ] Frontend : parcours de réservation complet, dashboard organisateur
- [ ] Tests d'intégration Testcontainers sur chaque service
- [ ] Pipeline CI/CD GitHub Actions (build, tests, images Docker)
- [ ] Observabilité (Actuator, tracing, dashboards)

Détail complet de chaque lot dans le [cahier des charges](./CAHIER_DES_CHARGES.md).

## Patterns mis en oeuvre

- **Database per service** — chaque service est propriétaire de ses données.
- **Saga orchestrée** — le service Réservations pilote le flux et déclenche les actions compensatoires en cas d'échec de paiement.
- **Outbox pattern** — publication fiable des événements sans double-écrit DB/broker.
- **Idempotence** — clé d'idempotence sur les paiements, déduplication à la consommation des messages.
- **Resource server découplé** — chaque service valide lui-même son JWT (pas de session partagée), Keycloak reste la seule source de vérité sur l'identité.

## Tests

- Unitaires : JUnit 5 + Mockito sur la logique métier de chaque service.
- Intégration : Testcontainers (Postgres, RabbitMQ réels) — voir `event-service` pour un premier exemple à dupliquer.
- Frontend : Vitest pour les composants, Cypress/Playwright prévu pour l'E2E.

## Licence

MIT — voir [`LICENSE`](./LICENSE).
