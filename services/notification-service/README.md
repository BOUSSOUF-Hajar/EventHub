# notification-service

Service Notifications : il prévient le client par e-mail de l'issue de sa réservation.
C'est le dernier maillon de la Saga, purement consommateur : il ne publie aucun événement
et ne possède aucune donnée métier.

- **Port** : 8085 (uniquement `/actuator/health`)
- **Entrée** : RabbitMQ, événements `booking.confirmed` et `booking.cancelled`
- **Sortie** : SMTP — Mailhog en dev (`localhost:1025`, interface sur http://localhost:8025)
- **État** : Redis, pour la déduplication. Pas de base de données.

État : **lot 4 terminé**.

## Flux

```
booking.events ──booking.confirmed──► notification.booking-confirmed.queue ─┐
               ──booking.cancelled──► notification.booking-cancelled.queue ─┤
                                                                            ▼
                                                                  BookingEventListener
                                                                            │
                                                                   NotificationService
                                                          ┌─────────────────┼─────────────────┐
                                                          ▼                 ▼                 ▼
                                                  1. réserver         2. envoyer        3. marquer
                                                  l'événement         l'e-mail          « traité »
                                                    (Redis)            (SMTP)            (Redis)
```

| Événement | E-mail envoyé à `customerEmail` |
|---|---|
| `booking.confirmed` (NTF-1) | « Votre réservation est confirmée » : référence, nombre de places, montant |
| `booking.cancelled` (NTF-2) | « Votre réservation a été annulée », avec le motif traduit (`PAYMENT_FAILED` → paiement refusé, `PAYMENT_TIMEOUT` → délai dépassé) |

Un motif d'annulation inconnu donne un message générique plutôt qu'une erreur : mieux vaut
prévenir le client de façon vague que ne pas le prévenir.

L'e-mail est rédigé avec les seules données de l'événement. Le service n'appelle aucun
autre service pour les compléter (contrainte « pas d'appel REST direct entre services »).

## Déduplication (NTF-3)

RabbitMQ livre *at least once* : le même événement peut arriver deux fois, et le client ne
doit recevoir qu'un e-mail. La clé de déduplication est le **`messageId`** de l'événement,
celui que l'outbox de booking-service lui attribue.

Pourquoi `messageId` et pas `bookingId` ? Une même réservation peut légitimement donner
lieu à plusieurs notifications distinctes ; ce sont les copies d'un *même événement* qu'il
faut écarter.

### Pourquoi deux états

Envoyer un e-mail et écrire dans Redis ne peuvent pas être atomiques. Avec un seul
marqueur, il faut choisir entre deux défauts :

- **marquer puis envoyer** : un crash entre les deux perd l'e-mail ;
- **envoyer puis marquer** : deux copies traitées en parallèle passent toutes les deux la
  vérification, le client reçoit deux e-mails.

`ProcessedEventStore` procède donc en deux temps :

1. `SET notification:event:{messageId} PROCESSING NX EX 30` — réservation atomique, avec un
   **bail** court. Une seule instance peut l'obtenir ;
2. après l'envoi, `SET ... DONE EX 7d` — mémoire durable de l'événement traité.

| Résultat de la réservation | Signification | Action |
|---|---|---|
| obtenue | événement nouveau | envoyer, puis marquer `DONE` |
| `DONE` trouvé | doublon | acquitter sans rien envoyer |
| `PROCESSING` trouvé | une autre copie est en cours | réessayer plus tard |

Si le premier traitement a planté, son bail expire et la copie en attente prend le relais :
l'e-mail n'est pas perdu.

> Limite connue : un crash juste après l'envoi SMTP et avant le marquage `DONE` provoque un
> second e-mail à l'expiration du bail. Aucun protocole ne rend un envoi d'e-mail
> « exactement une fois » ; entre perdre un e-mail et l'envoyer deux fois, on préfère le
> doublon. La mémoire des événements traités est limitée à 7 jours.

## Gestion des erreurs

| Cas | Traitement |
|---|---|
| Message illisible ou incomplet (`messageId`, `bookingId` ou `customerEmail` absent) | Tracé en erreur puis acquitté : le représenter ne le rendrait pas valide |
| Doublon | Acquitté en silence |
| SMTP ou Redis indisponible | La réservation Redis est rendue, puis le message est retenté |
| E-mail parti mais marquage `DONE` impossible | Acquitté quand même : retenter renverrait l'e-mail à coup sûr |

Les essais sont espacés (1 s, 2 s, 4 s, 8 s puis 10 s, 7 tentatives), puis le message est
**remis en queue** et un nouveau cycle démarre : une panne SMTP, même longue, ne fait
perdre aucune notification. La durée d'un cycle (35 s) dépasse volontairement le bail de
déduplication (30 s), pour qu'une copie en attente finisse par pouvoir reprendre l'événement.

> Limite connue : il n'y a pas de dead-letter queue. Un message invalide est écarté avec
> une simple trace en log, et une adresse e-mail définitivement refusée par le serveur SMTP
> serait retentée sans fin.

## Topologie RabbitMQ

Les deux queues sont déclarées par booking-service, propriétaire de l'exchange, pour que
les événements émis avant le premier démarrage de notification-service ne soient pas
perdus. notification-service les redéclare à l'identique (opération idempotente) afin de
rester démarrable seul. Conséquence pratique : au démarrage, le service envoie les
notifications restées en attente.

## Configuration

| Propriété | Défaut | Rôle |
|---|---|---|
| `spring.mail.host` / `port` | `localhost` / `1025` | Serveur SMTP (Mailhog en dev) |
| `eventhub.notification.from` | `no-reply@eventhub.local` | Expéditeur |
| `eventhub.notification.deduplication.lease` | `30s` | Bail d'un événement en cours de traitement |
| `eventhub.notification.deduplication.retention` | `7d` | Mémoire d'un événement déjà notifié |
| `eventhub.rabbitmq.exchange` | `booking.events` | Exchange écouté |
| `eventhub.rabbitmq.routing-key.*` | `booking.confirmed/cancelled` | |
| `spring.rabbitmq.listener.simple.retry.*` | 7 essais, 1 s → 10 s | Espacement des essais |

Les délais SMTP (5 s) doivent rester inférieurs au bail de déduplication, sinon un envoi
lent pourrait encore être en cours quand une autre copie reprend l'événement.

## Lancer

```bash
# depuis la racine du dépôt
docker compose up -d rabbitmq redis mailhog

cd services/notification-service && mvn spring-boot:run
```

Pour voir un e-mail arriver : lancer aussi event-service, booking-service et
payment-service, créer une réservation, puis ouvrir http://localhost:8025.

## Tests

```bash
mvn test
```

19 tests. Les tests d'intégration tournent sur RabbitMQ, Redis et **Mailhog** réels
(Testcontainers, Docker doit tourner) : l'e-mail est relu dans Mailhog par son API HTTP,
celle qu'utilise son interface web.

| Classe | Ce qu'elle couvre |
|---|---|
| `NotificationFlowIntegrationTest` | `booking.confirmed` / `booking.cancelled` → e-mail reçu par Mailhog ; même événement deux fois → un seul e-mail ; message empoisonné qui ne bloque pas la queue ; événement réservé par un traitement planté, repris à l'expiration du bail |
| `NotificationServiceTest` | Ordre réserver → envoyer → marquer, échec SMTP, échec du marquage après envoi |
| `EmailComposerTest` | Contenu des e-mails, motifs d'annulation, champs optionnels absents |
