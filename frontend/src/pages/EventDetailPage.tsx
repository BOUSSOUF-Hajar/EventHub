import { useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { eventKeys, fetchEvent } from "../api/events";
import { bookingKeys, createBooking } from "../api/bookings";
import { ApiError, errorMessage } from "../api/client";
import { useSession } from "../auth/useSession";
import CapacityBar from "../components/CapacityBar";
import { Notice, Spinner } from "../components/Feedback";
import { availabilityOf, formatDateTime, formatPrice, pluralize } from "../lib/format";
import type { EventItem } from "../types";

/** Meme borne que le booking-service (CreateBookingRequest) : 10 places par reservation. */
const MAX_SEATS_PER_BOOKING = 10;

/** EVT-2 : detail public d'un evenement, avec le panneau de reservation. */
export default function EventDetailPage() {
  const { eventId = "" } = useParams();
  const event = useQuery({ queryKey: eventKeys.one(eventId), queryFn: () => fetchEvent(eventId) });

  if (event.isLoading) {
    return <Spinner label="Chargement de l'événement…" />;
  }
  if (event.isError || !event.data) {
    const notFound = event.error instanceof ApiError && event.error.status === 404;
    return (
      <Notice tone="error" title={notFound ? "Événement introuvable" : "L'événement n'a pas pu être chargé"}>
        <p>{notFound ? "Il a peut-être été supprimé par son organisateur." : errorMessage(event.error)}</p>
        <Link to="/" className="button button--ghost">
          Retour aux événements
        </Link>
      </Notice>
    );
  }

  return (
    <>
      <Link to="/" className="back-link">
        ← Tous les événements
      </Link>
      <div className="detail">
        <article className="detail__main">
          <h1 className="detail__title">{event.data.title}</h1>
          <dl className="facts">
            <div>
              <dt>Date</dt>
              <dd>{formatDateTime(event.data.startsAt)}</dd>
            </div>
            <div>
              <dt>Lieu</dt>
              <dd>{event.data.venue}</dd>
            </div>
            <div>
              <dt>Prix</dt>
              <dd>{formatPrice(event.data.unitPrice)} la place</dd>
            </div>
          </dl>
          <CapacityBar event={event.data} />
          {event.data.description && <p className="detail__description">{event.data.description}</p>}
        </article>

        <BookingPanel event={event.data} />
      </div>
    </>
  );
}

function BookingPanel({ event }: { event: EventItem }) {
  const session = useSession();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [seats, setSeats] = useState(1);

  const availability = availabilityOf(event);
  const maxSeats = Math.max(1, Math.min(MAX_SEATS_PER_BOOKING, event.remainingSeats));

  const booking = useMutation({
    mutationFn: () => createBooking(event.id, seats, session.token ?? ""),
    onSuccess: (created) => {
      queryClient.setQueryData(bookingKeys.one(created.id), created);
      void queryClient.invalidateQueries({ queryKey: bookingKeys.mine });
      // On enchaine directement sur le suivi : la reservation n'est encore que PENDING.
      navigate(`/bookings/${created.id}`);
    },
  });

  if (availability === "past") {
    return (
      <aside className="panel">
        <h2 className="panel__title">Réservation fermée</h2>
        <p className="panel__text">Cet événement a déjà commencé.</p>
      </aside>
    );
  }

  return (
    <aside className="panel">
      <h2 className="panel__title">Réserver</h2>

      <div className="stepper">
        <span className="stepper__label" id="seats-label">
          Nombre de places
        </span>
        <div className="stepper__controls" role="group" aria-labelledby="seats-label">
          <button
            className="stepper__button"
            onClick={() => setSeats((value) => Math.max(1, value - 1))}
            disabled={seats <= 1}
            aria-label="Retirer une place"
          >
            −
          </button>
          <output className="stepper__value" aria-live="polite">
            {seats}
          </output>
          <button
            className="stepper__button"
            onClick={() => setSeats((value) => Math.min(maxSeats, value + 1))}
            disabled={seats >= maxSeats}
            aria-label="Ajouter une place"
          >
            +
          </button>
        </div>
      </div>

      <div className="total">
        <span>
          {pluralize(seats, "place")} × {formatPrice(event.unitPrice)}
        </span>
        <strong>{formatPrice(seats * event.unitPrice)}</strong>
      </div>

      {booking.isError && (
        <Notice tone="error" title="La réservation n'a pas abouti">
          <p>{errorMessage(booking.error)}</p>
        </Notice>
      )}

      {session.isLoading ? null : !session.isAuthenticated ? (
        <>
          <button className="button button--primary button--block" onClick={() => session.login()}>
            Se connecter pour réserver
          </button>
          <p className="panel__hint">Vous reviendrez sur cette page après la connexion.</p>
        </>
      ) : !session.canBook ? (
        <Notice tone="warning">
          <p>
            Le compte <strong>{session.username}</strong> n'a pas le rôle client : il ne peut pas réserver.
          </p>
        </Notice>
      ) : (
        <>
          <button
            className="button button--primary button--block"
            onClick={() => booking.mutate()}
            disabled={booking.isPending}
          >
            {booking.isPending ? "Réservation en cours…" : `Réserver ${pluralize(seats, "place")}`}
          </button>
          <p className="panel__hint">
            {availability === "sold-out"
              ? "Le catalogue indique complet : des places peuvent toutefois se libérer."
              : "Paiement simulé : vous suivrez chaque étape sur l'écran suivant."}
          </p>
        </>
      )}
    </aside>
  );
}
