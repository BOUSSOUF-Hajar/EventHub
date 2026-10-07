# payment-service

Service Paiements : il tente le paiement (simulé) de chaque réservation demandée et publie
le résultat, que booking-service utilise pour confirmer la réservation ou la compenser.

- **Port** : 8084
- **Base** : PostgreSQL `payment_db` (port 5435, conteneur `eventhub-postgres-payment`)
- **Entrée / sortie** : RabbitMQ uniquement. Le service n'expose aucun endpoint HTTP métier,
  seulement `/actuator/health`.

État : **lot 3 terminé**.

## Flux

```
booking.events ──booking.requested──► payment.booking-requested.queue
                                               │
                                     BookingRequestedListener
                                               │
                              PaymentService (1 transaction)
                              ├─ INSERT payments
                              └─ INSERT outbox_events
                                               │
                                          OutboxRelay
                                               │
payment.events ──payment.succeeded / payment.failed──► booking.payment-result.queue
```

Un paiement **refusé** n'est pas une erreur technique : c'est un résultat métier, enregistré
(`FAILED`) et publié exactement comme un succès. Seule une panne (base ou PSP injoignable)
fait échouer le traitement, et le message est alors relivré.

## Idempotence (PAY-2)

RabbitMQ garantit une livraison *at least once* : le même `booking.requested` peut arriver
deux fois. La clé d'idempotence est le **`bookingId`**, protégé par la contrainte unique
`uk_payments_booking_id` : une réservation ne se paie qu'une fois.

Pourquoi `bookingId` plutôt que le `messageId` du message ? Le `messageId` ne couvre que la
relivraison d'un même message. Avec `bookingId`, une demande réémise sous un autre
identifiant est écartée elle aussi.

Deux niveaux de protection :

1. une vérification `existsByBookingId` avant traitement — le cas courant, sans appel au PSP ;
2. la **contrainte unique** — le vrai garde-fou. La vérification seule ne protège pas de
   deux instances traitant le même message au même instant ; la base, si. La violation est
   reconnue comme un doublon et le message est acquitté.

> Limite connue : entre un débit accepté par le PSP et le commit en base, un crash provoque
> une relivraison, donc un second appel au PSP. C'est pour cela que `PaymentGateway.charge`
> reçoit la clé d'idempotence : un vrai PSP s'en sert pour refuser le doublon de son côté.
> Le PSP simulé l'ignore, il n'y a pas de débit réel.

## Pattern Outbox (PAY-3)

Le paiement et la ligne `outbox_events` sont écrits **dans la même transaction**, puis
`OutboxRelay` publie les lignes en attente. Même mécanique que dans booking-service :
`FOR UPDATE SKIP LOCKED`, confirmations corrélées et flag `mandatory`, un événement n'étant
marqué publié que si le broker l'a acquitté *et* routé vers une queue.

Le code de l'outbox est volontairement **dupliqué** depuis booking-service plutôt que
partagé dans une librairie : chaque service reste propriétaire de son code et de sa base.

## Paiement simulé et taux d'échec (PAY-1, PAY-4)

`SimulatedPaymentGateway` refuse une part configurable des paiements :

```yaml
payment:
  failure-rate: 0.0   # 0.0 = jamais refusé, 1.0 = toujours refusé
```

Pour démontrer la compensation de la Saga :

```bash
mvn spring-boot:run "-Dspring-boot.run.arguments=--payment.failure-rate=1.0"
```

Une valeur hors de `[0, 1]` empêche le service de démarrer. `PaymentGateway` est une
interface : un vrai PSP s'y brancherait sans toucher au reste du service.

## Contrats d'événements

Publiés sur l'exchange `payment.events` (topic, durable).

`payment.succeeded` :

```json
{
  "messageId": "0b6f1c0e-3c0b-4a55-9a0d-6f2a8a3a0c11",
  "type": "payment.succeeded",
  "occurredAt": "2026-10-07T20:37:39.104Z",
  "paymentId": "ca4606bc-0392-47ac-a474-a6d1c7eb0765",
  "bookingId": "ba1bb2be-7e5b-4a6e-8e02-6dbf3c80ce9a",
  "amount": 40.00
}
```

`payment.failed` : mêmes champs, avec en plus `"reason": "CARD_DECLINED"`.

À la consommation, seuls `bookingId` et `amount` de `booking.requested` sont lus ; les
autres champs sont ignorés, ce qui laisse booking-service enrichir son événement sans
casser ce service.

payment-service déclare la queue `booking.payment-result.queue` de son consommateur, pour
la même raison que booking-service déclare celle de payment-service : un message publié
sur un topic sans binding est jeté sans erreur.

## Gestion des erreurs à la consommation

| Cas | Traitement |
|---|---|
| Message illisible ou incomplet (`bookingId` absent, montant ≤ 0) | Rejeté **sans** remise en queue : le relivrer ne le rendrait pas valide |
| Doublon | Acquitté en silence |
| Panne technique (base indisponible…) | L'exception remonte, RabbitMQ relivre |

> Limite connue : il n'y a pas de dead-letter queue. Un message rejeté est perdu (tracé en
> log), et une panne durable de la base provoque une relivraison en boucle sans délai.

## Configuration

| Propriété | Défaut | Rôle |
|---|---|---|
| `payment.failure-rate` | `0.0` | Part des paiements refusés |
| `eventhub.rabbitmq.exchange` | `payment.events` | Exchange des résultats |
| `eventhub.rabbitmq.routing-key.*` | `payment.succeeded/failed` | |
| `eventhub.rabbitmq.booking.exchange` | `booking.events` | Exchange écouté |
| `eventhub.rabbitmq.booking.requested-routing-key` | `booking.requested` | |
| `eventhub.outbox.poll-interval-ms` | `1000` | Fréquence du relais |
| `eventhub.outbox.batch-size` | `100` | Taille de lot |
| `eventhub.outbox.confirm-timeout-ms` | `5000` | Attente de confirmation broker |

## Lancer

```bash
# depuis la racine du dépôt
docker compose up -d postgres-payment rabbitmq

cd services/payment-service && mvn spring-boot:run
```

## Tests

```bash
mvn test
```

13 tests. Les tests d'intégration tournent sur Postgres et RabbitMQ **réels**
(Testcontainers, Docker doit tourner).

| Classe | Ce qu'elle couvre |
|---|---|
| `PaymentFlowIntegrationTest` | `booking.requested` → `payment.succeeded` / `payment.failed` sur le vrai broker ; même message livré deux fois → un seul débit ; message empoisonné qui ne bloque pas la queue |
| `PaymentServiceTest` | Succès, refus, doublon, violation de la contrainte unique |
| `SimulatedPaymentGatewayTest` | Bornes du taux d'échec, configuration invalide |

Dans `PaymentFlowIntegrationTest`, seul le PSP est simulé : c'est la seule dépendance
extérieure du service, et le piloter rend le chemin d'échec déterministe.
