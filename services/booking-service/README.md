# booking-service

Service Réservations : il crée les réservations, garantit qu'on ne vend jamais plus de
places que la capacité, et sert d'**orchestrateur de la Saga** réservation → paiement →
confirmation.

État : **lot 3 terminé** (réservation, verrou, outbox, Saga complète avec compensation et
expiration).

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
réservation par réservation, par un job dédié (voir [Saga](#saga-réservation--paiement)).

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

## Saga réservation → paiement

booking-service orchestre ; payment-service ne connaît que son propre travail.

```
POST /api/bookings ─► PENDING ──(relais)──► booking.requested ─► payment-service
                                                                       │
                      ┌──────── payment.succeeded ◄────────────────────┤
                      │                                                │
                      ▼                                                ▼
                  CONFIRMED                                     payment.failed
              + booking.confirmed                                      │
                                                                       ▼
                                                                   CANCELLED
                                                     + places rendues + booking.cancelled
```

`PaymentResultListener` écoute `booking.payment-result.queue` et délègue à `BookingSaga` :

| Événement reçu | Effet | Événement émis |
|---|---|---|
| `payment.succeeded` | `CONFIRMED`, les places restent comptées (BKG-4) | `booking.confirmed` |
| `payment.failed` | `CANCELLED` + libération des places (BKG-5) | `booking.cancelled` (`PAYMENT_FAILED`) |
| aucun, délai dépassé | `CANCELLED` + libération des places (BKG-3) | `booking.cancelled` (`PAYMENT_TIMEOUT`) |

### Doublons et messages en retard

RabbitMQ livre *at least once* et sans garantie d'ordre entre deux sources. Chaque
transition relit donc la réservation **sous verrou** (`SELECT ... FOR UPDATE`) et laisse la
machine à états arbitrer :

- un `payment.failed` livré deux fois ne rend les places qu'une fois — la seconde fois, la
  réservation est déjà `CANCELLED` ;
- un `payment.failed` arrivant après un `payment.succeeded` est ignoré ;
- le résultat peut arriver avant que le relais ait marqué `AWAITING_PAYMENT` : c'est pour
  cela que ce passage est un `UPDATE ... WHERE status = 'PENDING'`, qui ne peut pas écraser
  un `CONFIRMED` tout juste validé ;
- un message illisible, de type inconnu ou visant une réservation inconnue est écarté sans
  remise en queue : le relivrer ne le rendrait pas applicable, il bloquerait la queue.

### Pourquoi les places sont rendues *après* le commit

Redis n'est pas dans la transaction de la base : il faut choisir un ordre, et les deux ont
une faille.

- **Rendre avant le commit** : si le commit échoue, le message est relivré et les places
  sont rendues une seconde fois. Le compteur passe sous la réalité → sur-réservation.
- **Rendre après le commit** : si la libération échoue, des places restent bloquées à tort.
  C'est tracé en erreur et rattrapable, sans jamais vendre une place deux fois.

On retient le second : des places bloquées à tort se corrigent, une sur-réservation non.

### Expiration des réservations impayées (BKG-3)

`BookingExpirationJob` annule périodiquement les réservations restées `PENDING` ou
`AWAITING_PAYMENT` au-delà de `eventhub.booking.payment-timeout` (15 min par défaut). C'est
le filet de sécurité de la Saga si le résultat du paiement n'arrive jamais. Chaque
réservation est traitée dans sa propre transaction et relue sous verrou, ce qui écarte
celles payées entre-temps.

> Limites connues :
> - un paiement accepté **après** l'expiration ne ressuscite pas la réservation (ses places
>   sont peut-être revendues). Le cas est tracé en erreur ; le remboursement n'est pas
>   implémenté.
> - si la libération Redis échoue après l'annulation, le compteur doit être réconcilié à la
>   main : aucune reprise automatique n'est en place.

## Contrats d'événements

Publiés sur l'exchange `booking.events` (topic, durable). Routing key `booking.requested` :

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

`booking.confirmed` reprend les mêmes champs métier. `booking.cancelled` remplace `amount`
par `reason` (`PAYMENT_FAILED` ou `PAYMENT_TIMEOUT`).

| Queue | Routing key | Consommateur |
|---|---|---|
| `payment.booking-requested.queue` | `booking.requested` | payment-service |
| `event.booking-confirmed.queue` | `booking.confirmed` | event-service (places restantes du catalogue) |
| `notification.booking-confirmed.queue` | `booking.confirmed` | notification-service |
| `notification.booking-cancelled.queue` | `booking.cancelled` | notification-service |

booking-service déclare lui-même les queues de ses consommateurs et leurs bindings.
Ce choix est assumé : un message publié sur un topic sans binding est jeté sans erreur, donc
laisser chaque consommateur déclarer sa queue reviendrait à perdre tous les événements émis
tant que ce consommateur n'a jamais démarré. Les consommateurs redéclarent la même queue de
leur côté — l'opération est idempotente et chacun reste démarrable seul. Conséquence : tant
que notification-service est arrêté, les événements `booking.confirmed` et
`booking.cancelled` s'accumulent dans leurs queues au lieu d'être perdus, et les e-mails
partent à son redémarrage.

## Machine à états

```
PENDING ──► AWAITING_PAYMENT ──► CONFIRMED
   │               │
   └───────────────┴────────────► CANCELLED
```

`CONFIRMED` et `CANCELLED` sont terminaux. C'est ce qui protège la Saga d'un message en
retard : un `payment.failed` arrivant après un `payment.succeeded` ne peut pas annuler une
réservation déjà confirmée. Rejouer une transition déjà appliquée est sans effet, ce qui
rend le listener de paiement idempotent.

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
| `eventhub.rabbitmq.payment.exchange` | `payment.events` | Exchange de payment-service |
| `eventhub.rabbitmq.payment.routing-key.*` | `payment.succeeded/failed` | |
| `eventhub.booking.payment-timeout` | `15m` | Délai avant expiration d'une réservation impayée |
| `eventhub.booking.expiration-poll-interval-ms` | `60000` | Fréquence du job d'expiration |
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

# pour que les réservations sortent de AWAITING_PAYMENT
cd services/payment-service && mvn spring-boot:run
```

## Tests

```bash
mvn test
```

72 tests, dont les tests d'intégration sur Postgres, Redis et RabbitMQ **réels**
(Testcontainers). Ce qu'on valide ici — atomicité du script Lua, `FOR UPDATE SKIP LOCKED`,
confirmations de publication — n'existe tout simplement pas dans un double de test.

| Classe | Ce qu'elle couvre |
|---|---|
| `SeatLockServiceConcurrencyTest` | 2 threads sur la dernière place → 1 seul succès ; 50 threads / 10 places → jamais 11 |
| `BookingServiceIntegrationTest` | Même chose au niveau réservation ; 20 clients / 5 places → 5 réservations et 5 lignes d'outbox |
| `OutboxRelayIntegrationTest` | Publication réelle, non-republication, et conservation des messages non routables |
| `SagaIntegrationTest` | Les deux issues de la Saga sur le vrai broker : paiement réussi → `CONFIRMED` ; paiement échoué → `CANCELLED` + places restituées ; doublons, message en retard, message empoisonné, expiration |
| `BookingSagaTest` | Ordre « commit puis libération », échec de commit, panne Redis à la libération, paiement arrivé après expiration |
| `SeatLockServiceFailureTest` | Une panne Redis remonte en 503, jamais en « complet » |
| `BookingControllerTest` | 401 / 403 / 409 / 400, identité issue du jeton |
| `BookingStatusTest` | Transitions interdites et rejeu de messages |

Les conteneurs sont démarrés une seule fois pour toute la JVM ; l'état est remis à zéro
avant chaque test.

`SagaIntegrationTest` tient le rôle de payment-service : il lit la demande réellement
publiée, puis répond sur `payment.events` avec le message que payment-service émet. La
moitié « paiement » du scénario est vérifiée dans `PaymentFlowIntegrationTest`, côté
payment-service. Les deux services étant des modules Maven distincts, aucun test automatisé
ne les démarre ensemble ; le scénario complet se rejoue à la main avec les deux services
lancés (`payment.failure-rate=1.0` pour le chemin d'échec).
