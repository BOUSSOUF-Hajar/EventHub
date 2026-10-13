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

Les URLs des services sont configurables, `localhost` n'étant que la valeur par défaut : `eventhub.services.event-service-url`, `eventhub.services.booking-service-url` et `eventhub.services.payment-service-url` (ou, en variable d'environnement, `EVENTHUB_SERVICES_EVENT_SERVICE_URL`, etc.).

## Résilience

Chaque route passe par son propre circuit breaker (Resilience4j), pour qu'un service en panne ne dégrade pas les autres :

- un échec de connexion ou un appel de plus de 5 s compte comme un échec ; une réponse 4xx ou 5xx du service est relayée telle quelle ;
- le circuit s'ouvre à partir de 50 % d'échecs sur les 10 derniers appels (5 appels minimum), reste ouvert 10 s, puis laisse passer 2 appels d'essai ;
- en cas d'échec ou de circuit ouvert, le Gateway répond `503` au format RFC 7807 (`FallbackController`), avec un `detail` affichable par le frontend et le nom du service concerné.

La sécurité s'applique avant le repli : une route protégée appelée sans JWT renvoie toujours 401, même si son service est arrêté.

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

`GatewayRoutingTest` vérifie le routage et la résilience face à de faux services : un serveur HTTP local joue event-service et booking-service, et payment-service pointe sur un port fermé. Il couvre le relais de la requête et du jeton, la réponse 503 de repli, et l'ouverture du circuit après des échecs répétés sans effet sur les autres routes. Pas de Testcontainers ici : le Gateway n'a ni base ni broker.

`mvn verify` ajoute le contrôle de couverture Jacoco (70 % de lignes minimum) et l'analyse SpotBugs.

Pour tester à la main, importer `postman/EventHub.postman_collection.json` et utiliser le dossier « Gateway ».
