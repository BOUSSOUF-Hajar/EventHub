# booking-service

Service Réservations : il crée les réservations, garantit qu'on ne vend jamais plus de
places que la capacité, et sert d'**orchestrateur de la Saga** réservation → paiement →
confirmation.

État : **lot 2 terminé** (réservation, verrou, outbox). Le lot 3 y branchera l'écoute des
événements de paiement.

## Endpoints

| Méthode | Chemin | Rôle requis | Description |
|---|---|---|---|
| `POST` | `/api/bookings` | `CUSTOMER` ou `ADMIN` | Crée une réservation en statut `PENDING` |
| `GET` | `/api/bookings/me` | authentifié | Réservations du porteur du jeton |
| `GET` | `/api/bookings/{id}` | authentifié | Détail, uniquement si la réservation lui appartient |
| `GET` | `/actuator/health` | public | Sonde de santé |

Codes de retour notables :

| Code | Cas |
|---|---|
| `201` | Réservation créée |
| `400` | `seatCount` hors bornes (1 à 10) ou `eventId` absent |
| `401` | Jeton absent, ou `sub` qui n'est pas un UUID Keycloak |
| `403` | Jeton valide sans le rôle `CUSTOMER` |
| `404` | Événement inconnu du catalogue |
| `409` | Plus assez de places, événement déjà commencé ou prix non renseigné |
| `503` | event-service ou Redis injoignable |

L'identité du client vient **toujours** du JWT (`sub`, `email`), jamais du corps de la
requête : un `customerId` envoyé par le client est ignoré.

## Comment la sur-réservation est empêchée (BKG-2)

La capacité appartient à event-service, la réservation à booking-service : il n'existe
aucune ligne commune à verrouiller en base. Le point de sérialisation est donc **Redis**,
avec une clé par événement :

```
seats:{eventId} = nombre de places actuellement prises
```

La réservation passe par un **script Lua**, exécuté de façon atomique par Redis :

```lua
local reserved = tonumber(redis.call('GET', KEYS[1]) or '0')
if reserved + count > capacity then return -1 end
return redis.call('INCRBY', KEYS[1], count)
```

Le « lire, comparer, incrémenter » ne peut donc pas être entrelacé avec une autre requête.
C'est préférable à `WATCH`/`MULTI`/`EXEC`, qui imposerait une boucle de retry sur conflit
optimiste et s'effondrerait précisément sous la contention qui nous intéresse : la ruée
sur les dernières places.

La clé n'a **pas** de TTL : un TTL sur le compteur agrégé ferait réapparaître d'un coup
des places déjà confirmées. L'expiration des réservations impayées (BKG-3) se traite
réservation par réservation, au lot 3.

> Limite connue : si Redis est purgé, le compteur doit être reconstruit depuis la base
> (somme des places des réservations non annulées). Aucune reconstruction automatique
> n'est en place à ce stade.

## Pattern Outbox

Écrire en base et publier sur RabbitMQ sont deux systèmes distincts : on ne peut pas les
rendre atomiques. Publier d'abord risque d'annoncer un événement qui n'aura pas lieu ;
committer d'abord risque de le perdre.

La réservation et sa ligne `outbox_events` sont donc écrites **dans la même transaction**.
Un relais (`OutboxRelay`, `@Scheduled`) publie ensuite les lignes non publiées :

1. `SELECT ... FOR UPDATE SKIP LOCKED` — plusieurs instances peuvent tourner en parallèle
   sans se bloquer ni publier deux fois ;
2. envoi avec **confirmations corrélées** et flag `mandatory` ;
3. `publishedAt` renseigné **seulement** si le broker a acquitté *et* n'a pas renvoyé le
   message. Un ACK seul ne suffit pas : un message qu'aucune queue n'accepte est acquitté
   puis jeté silencieusement par RabbitMQ.

Garantie résultante : **at least once**. Chaque payload porte un `messageId` qui sert de
clé de déduplication aux consommateurs.

## Contrat d'événement

Publié sur l'exchange `booking.events` (topic, durable), routing key `booking.requested` :

```json
{
  "messageId": "bd302f20-2265-4cc0-9015-2b96a5c061a6",
  "type": "booking.requested",
  "occurredAt": "2026-08-08T18:52:12.618768900Z",
  "bookingId": "97409149-0472-43a1-9dfb-d2aa2300b677",
  "eventId": "97682e06-0f62-406b-9c6a-137e3ad7d865",
  "customerId": "2a2e8fc9-a6fa-47c6-a58a-14ce0548e846",
  "customerEmail": "alice@example.com",
  "seatCount": 1,
  "amount": 30.00
}
```

booking-service déclare lui-même la queue `payment.booking-requested.queue` et son binding.
Ce choix est assumé : un message publié sur un topic sans binding est jeté sans erreur, donc
laisser chaque consommateur déclarer sa queue reviendrait à perdre tous les événements émis
tant que ce consommateur n'a jamais démarré. Les consommateurs redéclarent la même queue de
leur côté — l'opération est idempotente et chacun reste démarrable seul.

## Machine à états

```
PENDING ──► AWAITING_PAYMENT ──► CONFIRMED
   │               │
   └───────────────┴────────────► CANCELLED
```

`CONFIRMED` et `CANCELLED` sont terminaux. C'est ce qui protège la Saga d'un message en
retard : un `payment.failed` arrivant après un `payment.succeeded` ne peut pas annuler une
réservation déjà confirmée. Rejouer une transition déjà appliquée est sans effet, ce qui
rend les futurs listeners idempotents.

Le passage `PENDING → AWAITING_PAYMENT` est déclenché par le relais, une fois la demande
réellement publiée : tant que le message n'est pas parti, la réservation n'attend rien.

## Appel à event-service

Seul appel REST synchrone du service, pour lire capacité et prix avant de décider. La
contrainte du cahier des charges (« pas d'appel REST direct ») porte sur les échanges entre
booking / payment / notification, qui passent bien par RabbitMQ.

Il est fait **hors transaction** (on ne garde pas une connexion base ouverte pendant un
appel réseau) et borné par des timeouts explicites : sans eux, un event-service qui accepte
la connexion mais ne répond jamais bloquerait un thread Tomcat indéfiniment.

## Configuration

| Propriété | Défaut | Rôle |
|---|---|---|
| `eventhub.event-service.base-url` | `http://localhost:8082` | Catalogue d'événements |
| `eventhub.event-service.connect-timeout` | `2s` | |
| `eventhub.event-service.read-timeout` | `3s` | |
| `eventhub.rabbitmq.exchange` | `booking.events` | |
| `eventhub.rabbitmq.routing-key.*` | `booking.requested/confirmed/cancelled` | |
| `eventhub.outbox.poll-interval-ms` | `1000` | Fréquence du relais |
| `eventhub.outbox.batch-size` | `100` | Taille de lot |
| `eventhub.outbox.confirm-timeout-ms` | `5000` | Attente de confirmation broker |

## Lancer

```bash
# depuis la racine du dépôt : Postgres, Redis, RabbitMQ, Keycloak
docker compose up -d

# event-service doit tourner (la capacité vient de lui)
cd services/event-service && mvn spring-boot:run

cd services/booking-service && mvn spring-boot:run
```

## Tests

```bash
mvn test
```

35 tests, dont les tests d'intégration sur Postgres, Redis et RabbitMQ **réels**
(Testcontainers). Ce qu'on valide ici — atomicité du script Lua, `FOR UPDATE SKIP LOCKED`,
confirmations de publication — n'existe tout simplement pas dans un double de test.

| Classe | Ce qu'elle couvre |
|---|---|
| `SeatLockServiceConcurrencyTest` | 2 threads sur la dernière place → 1 seul succès ; 50 threads / 10 places → jamais 11 |
| `BookingServiceIntegrationTest` | Même chose au niveau réservation ; 20 clients / 5 places → 5 réservations et 5 lignes d'outbox |
| `OutboxRelayIntegrationTest` | Publication réelle, non-republication, et conservation des messages non routables |
| `SeatLockServiceFailureTest` | Une panne Redis remonte en 503, jamais en « complet » |
| `BookingControllerTest` | 401 / 403 / 409 / 400, identité issue du jeton |
| `BookingStatusTest` | Transitions interdites et rejeu de messages |

Les conteneurs sont démarrés une seule fois pour toute la JVM ; l'état est remis à zéro
avant chaque test.
