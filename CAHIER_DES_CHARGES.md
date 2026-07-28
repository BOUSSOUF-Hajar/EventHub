# Cahier des charges — EventHub

**Version** : 1.0
**Statut** : à réaliser
**Porteur du projet** : toi — projet personnel destiné à être présenté en entretien technique

## 1. Contexte et objectifs

EventHub est une plateforme permettant à des organisateurs de publier des événements (concerts, conférences, matchs) à capacité limitée, et à des clients de réserver des places en ligne avec paiement.

Le projet n'a pas vocation à être un produit commercial : son objectif est de **démontrer une maîtrise réaliste d'une architecture microservices** sur un cas métier qui justifie naturellement les patterns mis en œuvre (concurrence sur un stock limité, transaction distribuée réservation/paiement, communication asynchrone).

### 1.1 Objectifs pédagogiques

À l'issue du projet, tu dois être capable d'expliquer et de défendre, sans notes, les points suivants :

- pourquoi découper en microservices ici, et où se trouve la limite (à partir de quand ça devient pertinent) ;
- comment Keycloak centralise l'authentification et comment chaque service valide un JWT de façon autonome ;
- comment une Saga orchestrée gère une transaction qui traverse plusieurs services, et comment elle compense un échec ;
- pourquoi un Outbox pattern est nécessaire dès qu'on écrit en base ET qu'on publie un événement ;
- comment tu as testé un système distribué (Testcontainers) plutôt que de tout mocker ;
- comment ton pipeline CI/CD garantit qu'un commit cassé n'arrive jamais sur `main`.

## 2. Périmètre fonctionnel

Le périmètre est volontairement limité à 4 domaines métier. Chaque domaine correspond à un microservice.

### 2.1 Service Événements (catalogue)

| ID | User story | Critère d'acceptation |
|---|---|---|
| EVT-1 | En tant que visiteur, je peux consulter la liste des événements à venir sans être connecté | `GET /api/events` retourne 200 sans token |
| EVT-2 | En tant que visiteur, je peux consulter le détail d'un événement | `GET /api/events/{id}` retourne titre, lieu, date, places restantes |
| EVT-3 | En tant qu'organisateur, je peux créer un événement avec une capacité totale | `POST /api/events` refusé (403) sans le rôle `ORGANIZER` ou `ADMIN` |
| EVT-4 | En tant qu'organisateur, je peux supprimer un de mes événements | `DELETE /api/events/{id}` réservé aux mêmes rôles |
| EVT-5 | Le nombre de places restantes affiché reflète les réservations confirmées | mis à jour de façon asynchrone via l'événement `booking.confirmed` |

### 2.2 Service Réservations

| ID | User story | Critère d'acceptation |
|---|---|---|
| BKG-1 | En tant que client connecté, je peux demander à réserver N places pour un événement | `POST /api/bookings` crée une réservation en statut `PENDING` |
| BKG-2 | Le système empêche la sur-réservation quand deux clients réservent en même temps | verrou Redis (ou verrou optimiste DB) sur la disponibilité ; test de concurrence à fournir |
| BKG-3 | Une réservation `PENDING` expire si le paiement n'est pas finalisé dans un délai donné | TTL Redis ou job de purge ; passage en `CANCELLED` + libération des places |
| BKG-4 | Une réservation passe en `CONFIRMED` uniquement après confirmation du paiement | écoute de l'événement `payment.succeeded` |
| BKG-5 | Une réservation passe en `CANCELLED` si le paiement échoue, et les places sont restituées | écoute de `payment.failed`, action compensatoire |
| BKG-6 | En tant que client, je peux consulter mes réservations | `GET /api/bookings/me` filtré sur l'utilisateur du JWT |

### 2.3 Service Paiements

| ID | User story | Critère d'acceptation |
|---|---|---|
| PAY-1 | Le service tente un paiement simulé pour une réservation `AWAITING_PAYMENT` | déclenché par l'événement `booking.requested` |
| PAY-2 | Le paiement est idempotent : un même événement reçu deux fois ne débite pas deux fois | clé d'idempotence stockée et vérifiée avant traitement |
| PAY-3 | Le résultat du paiement est publié de façon fiable | pattern Outbox : écriture DB + ligne outbox dans la même transaction |
| PAY-4 | Un taux d'échec configurable permet de tester le chemin de compensation | propriété `payment.failure-rate` en config, utile pour les démos/tests |

### 2.4 Service Notifications

| ID | User story | Critère d'acceptation |
|---|---|---|
| NTF-1 | Le client reçoit un email de confirmation quand sa réservation est confirmée | consommation de `booking.confirmed`, envoi via Mailhog en dev |
| NTF-2 | Le client reçoit un email d'échec quand sa réservation est annulée | consommation de `booking.cancelled` |
| NTF-3 | La consommation des événements est idempotente | déduplication par identifiant d'événement |

### 2.5 Frontend

| ID | User story | Critère d'acceptation |
|---|---|---|
| FE-1 | Un visiteur peut parcourir le catalogue sans connexion | page d'accueil publique |
| FE-2 | Un visiteur peut se connecter via Keycloak (Authorization Code + PKCE) | redirection vers Keycloak, retour authentifié |
| FE-3 | Un client connecté peut réserver des places et suivre le statut de sa réservation | polling ou rafraîchissement manuel du statut |
| FE-4 | Un organisateur connecté a accès à un écran de création d'événement | écran conditionné au rôle `ORGANIZER` présent dans le token |

## 3. Exigences non fonctionnelles

| Catégorie | Exigence |
|---|---|
| Sécurité | Aucun endpoint d'écriture accessible sans JWT valide ; rôles vérifiés au niveau de chaque service (pas seulement au Gateway) |
| Résilience | Un service indisponible ne doit pas faire planter en cascade les autres (circuit breaker sur les appels synchrones via le Gateway) |
| Cohérence | Cohérence finale acceptée entre services (pas de transaction distribuée ACID) ; toute incohérence temporaire doit être résorbée par la Saga |
| Observabilité | Chaque service expose `/actuator/health` ; logs structurés exploitables |
| Testabilité | Couverture de tests minimum 70 % sur la logique métier des services Réservations et Paiements |
| Portabilité | L'ensemble du système démarre avec `docker compose up` sans configuration manuelle supplémentaire |

## 4. Contraintes techniques imposées

- Java 21 / Spring Boot 3.x pour tous les services backend.
- Une base PostgreSQL dédiée par service (pas de base partagée).
- Toute communication inter-service asynchrone passe par RabbitMQ — pas d'appel REST direct entre `booking-service`, `payment-service` et `notification-service`.
- Authentification déléguée à Keycloak — aucun stockage de mot de passe applicatif.
- Pas d'ORM "magique" cachant les requêtes N+1 : vérifier les requêtes générées par Hibernate sur les endpoints de liste.

## 5. Architecture cible (rappel)

Voir le diagramme dans le `README.md`. Synthèse :

```
Client React → API Gateway → {Événements, Réservations, Paiements}
                    ↕ (JWT)
                Keycloak

Réservations ↔ RabbitMQ ↔ Paiements
                    ↓
              Notifications
```

## 6. Découpage en lots

Chaque lot doit se terminer par : code committé, tests verts en local, README du service à jour.

### Lot 1 — Socle (fait dans ce squelette)
- Infrastructure Docker Compose
- Gateway + sécurité JWT
- Service Événements (CRUD complet + rôles)

**Definition of Done** : `docker compose up` démarre tout, le catalogue est consultable publiquement, la création d'événement est protégée par rôle.

### Lot 2 — Réservation et verrouillage
- Entité Booking + endpoints `POST /api/bookings`, `GET /api/bookings/me`
- Verrou Redis sur la disponibilité (clé = eventId, valeur = places réservées temporairement)
- Table outbox + relais de publication vers RabbitMQ
- Test de concurrence : deux requêtes simultanées sur la dernière place → une seule réussit

**Definition of Done** : test de concurrence automatisé et vert, événement `booking.requested` visible dans RabbitMQ management.

### Lot 3 — Paiement et Saga
- Entité Payment, endpoint interne déclenché par message RabbitMQ
- Idempotence (clé stockée en DB, contrainte unique)
- Publication `payment.succeeded` / `payment.failed`
- Dans Réservations : listener qui fait transitionner le statut et déclenche la compensation (libération des places) en cas d'échec

**Definition of Done** : scénario de bout en bout réservation → paiement réussi → confirmation, ET scénario réservation → paiement échoué → annulation + places restituées, tous deux couverts par un test d'intégration.

### Lot 4 — Notifications
- Listener `booking.confirmed` / `booking.cancelled`
- Envoi d'email via Mailhog
- Déduplication des événements déjà traités

**Definition of Done** : email visible dans l'UI Mailhog après une réservation confirmée en local.

### Lot 5 — Frontend complet
- Authentification Keycloak (Authorization Code + PKCE)
- Parcours réservation de bout en bout
- Écran organisateur de création d'événement

**Definition of Done** : démo manuelle complète réalisable sans toucher à un terminal.

### Lot 6 — Qualité et CI/CD
- Tests Testcontainers sur chaque service backend
- Pipeline GitHub Actions : build + tests + analyse statique + build des images Docker
- Couverture de code suivie (Jacoco) avec seuil minimum

**Definition of Done** : badge CI vert affiché dans le README, pipeline qui bloque un commit si les tests échouent.

### Lot 7 (optionnel, bonus entretien) — Observabilité
- Tracing distribué (OpenTelemetry + Jaeger ou Zipkin)
- Dashboard Grafana basique sur les métriques Actuator/Prometheus

**Definition of Done** : capacité à montrer en démo une trace qui traverse Gateway → Réservations → Paiements.

## 7. Livrables attendus

- Code source complet sur un repo GitHub public, avec historique de commits lisible (pas un seul commit "final version").
- `README.md` à jour avec instructions de démarrage fonctionnelles.
- `CAHIER_DES_CHARGES.md` (ce document) tenu à jour si le périmètre évolue.
- Au moins une vidéo ou un GIF de démo de 1 à 2 minutes (à ajouter en lien dans le README une fois le frontend fonctionnel).
- Pipeline CI visible et vert sur GitHub Actions.

## 8. Glossaire

- **Saga** : séquence de transactions locales coordonnée par un service orchestrateur, avec des actions compensatoires si une étape échoue.
- **Outbox pattern** : on écrit l'événement à publier dans une table de la même base, dans la même transaction que la donnée métier, pour garantir qu'on ne perd jamais un événement même si le broker est temporairement indisponible.
- **Idempotence** : propriété d'une opération qui peut être exécutée plusieurs fois sans changer le résultat au-delà du premier appel — indispensable quand un message peut être livré plusieurs fois.
- **Database per service** : chaque microservice est seul propriétaire de sa base de données ; les autres services n'y accèdent jamais directement.
- **Resource server** : rôle joué par chaque microservice qui valide lui-même les JWT émis par Keycloak, sans dépendre d'une session centralisée.
