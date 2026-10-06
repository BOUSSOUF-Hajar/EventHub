# event-service

Catalogue des événements d'EventHub : consultation publique, création et suppression réservées aux organisateurs.

- **Port** : 8082
- **Base** : PostgreSQL `event_db` (port 5433, conteneur `eventhub-postgres-event`)
- **Sécurité** : resource server OAuth2, JWT émis par Keycloak (realm `eventhub`)

## Endpoints

| Méthode | Chemin | Accès | Réponse |
|---|---|---|---|
| GET | `/api/events` | public | 200, liste des événements |
| GET | `/api/events/{id}` | public | 200, ou 404 si l'id est inconnu |
| POST | `/api/events` | rôle `ORGANIZER` ou `ADMIN` | 201, événement créé |
| DELETE | `/api/events/{id}` | rôle `ORGANIZER` ou `ADMIN` | 204 |
| GET | `/actuator/health` | public | 200 `{"status":"UP"}` |

Sans token, une écriture renvoie 401 ; avec un token sans le bon rôle, 403.

Corps attendu par `POST /api/events` :

```json
{
  "title": "Concert Jazz",
  "description": "Soirée jazz en plein air",
  "venue": "Théâtre Mohammed V, Rabat",
  "startsAt": "2027-01-15T20:00:00Z",
  "totalCapacity": 200,
  "remainingSeats": 200,
  "unitPrice": 35.00
}
```

`title`, `venue`, `startsAt`, `totalCapacity` et `remainingSeats` sont obligatoires (400 sinon). `unitPrice` (prix d'une place, utilisé par booking-service pour calculer le montant) vaut 0 s'il est omis.

## Sécurité

Le service valide lui-même chaque JWT auprès de Keycloak (`issuer-uri`), sans dépendre du Gateway. Keycloak place les rôles dans le claim `realm_access.roles` ; `SecurityConfig` les convertit en authorities `ROLE_...` pour que `@PreAuthorize("hasRole('ORGANIZER')")` fonctionne.

## Lancer

```bash
# depuis la racine du dépôt : Postgres et Keycloak
docker compose up -d postgres-event keycloak

cd services/event-service
mvn spring-boot:run
```

## Tester

```bash
mvn test
```

`EventControllerIntegrationTest` démarre un vrai PostgreSQL avec Testcontainers (Docker doit tourner) et vérifie les critères EVT-1 à EVT-4. Keycloak n'est pas nécessaire : seul le décodage du JWT est simulé, la conversion des rôles est celle du service.

Pour tester à la main, importer `postman/EventHub.postman_collection.json` et utiliser le dossier « Event Service ».

## Limites connues

- Un événement n'a pas de propriétaire : tout organisateur peut supprimer l'événement d'un autre (EVT-4 parle de « mes événements »).
- `POST` reçoit directement l'entité `Event` ; un DTO dédié avec validation métier (date future, capacité > 0) reste à écrire.
- `remainingSeats` n'est pas encore mis à jour par `booking.confirmed` (EVT-5, lots suivants).
