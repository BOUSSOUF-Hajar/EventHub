# gateway

Point d'entrée unique d'EventHub (Spring Cloud Gateway) : route les appels du frontend vers les services et impose un JWT sur tout ce qui n'est pas public.

- **Port** : 8080
- **Sécurité** : resource server OAuth2, JWT émis par Keycloak (realm `eventhub`)

## Routes

| Chemin | Service cible | Accès au niveau du Gateway |
|---|---|---|
| `/api/events/**` | event-service, `http://localhost:8082` | public |
| `/api/bookings/**` | booking-service, `http://localhost:8083` | JWT obligatoire |
| `/api/payments/**` | payment-service, `http://localhost:8084` | JWT obligatoire |
| `/actuator/health` | le Gateway lui-même | public |

Toute autre requête sans JWT valide renvoie 401.

## Sécurité

Le Gateway ne vérifie que la présence d'un JWT valide, pas les rôles. `/api/events/**` est ouvert en entier parce que la lecture du catalogue est publique ; ce sont les services qui refusent les écritures sans le bon rôle (event-service renvoie 401 ou 403 sur `POST` et `DELETE`). Chaque service reste donc protégé même s'il est appelé sans passer par le Gateway.

CORS : seule l'origine du frontend, `http://localhost:5173`, est autorisée. La configuration CORS est appliquée par la chaîne de sécurité elle-même (`.cors()`), donc **avant** l'authentification : une pré-requête `OPTIONS` ne porte jamais de jeton, et un filtre CORS placé après la sécurité la laisserait rejeter en 401 sur les routes protégées, ce qui bloquerait dans le navigateur tous les appels authentifiés.

## Lancer

```bash
# depuis la racine du dépôt
docker compose up -d keycloak

cd services/gateway
mvn spring-boot:run
```

Les services cibles doivent tourner pour que leurs routes répondent.

## Tester

```bash
mvn test
```

`GatewaySecurityTest` vérifie les règles propres au Gateway (health public, JWT obligatoire sur réservations et paiements, catalogue non bloqué, CORS y compris la pré-requête d'une route protégée) sans Keycloak ni service cible démarrés.

Pour tester à la main, importer `postman/EventHub.postman_collection.json` et utiliser le dossier « Gateway ».

## Limites connues

- Pas de circuit breaker sur les routes (exigence de résilience du cahier des charges).
- Les URLs des services sont en dur sur `localhost` ; à externaliser pour un déploiement Docker.
