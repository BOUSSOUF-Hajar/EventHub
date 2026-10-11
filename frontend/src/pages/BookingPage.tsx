import { useEffect } from "react";
import { Link, useParams } from "react-router-dom";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { bookingKeys, fetchBooking } from "../api/bookings";
import { eventKeys, fetchEvent } from "../api/events";
import { ApiError, errorMessage } from "../api/client";
import { useSession } from "../auth/useSession";
import { Notice, Spinner } from "../components/Feedback";
import SagaTimeline from "../components/SagaTimeline";
import StatusBadge from "../components/StatusBadge";
import { formatDateTime, formatPrice, formatShortDateTime, pluralize, shortReference } from "../lib/format";
import { isTerminal } from "../lib/saga";

const POLL_INTERVAL_MS = 1000;
const MAILHOG_URL = import.meta.env.VITE_MAILHOG_URL ?? "http://localhost:8025";

/** FE-3 : suivi du statut d'une reservation, etape par etape. */
export default function BookingPage() {
  const { bookingId = "" } = useParams();
  const session = useSession();
  const queryClient = useQueryClient();

  const booking = useQuery({
    queryKey: bookingKeys.one(bookingId),
    queryFn: () => fetchBooking(bookingId, session.token ?? ""),
    enabled: Boolean(session.token),
    // La Saga est asynchrone : on interroge le statut tant qu'il peut encore changer,
    // puis on s'arrete des qu'il est terminal.
    refetchInterval: (query) =>
      query.state.data && isTerminal(query.state.data.status) ? false : POLL_INTERVAL_MS,
  });

  const eventId = booking.data?.eventId ?? "";
  const event = useQuery({
    queryKey: eventKeys.one(eventId),
    queryFn: () => fetchEvent(eventId),
    enabled: eventId !== "",
    retry: false,
  });

  // Une fois la Saga terminee, les places restantes du catalogue et la liste des
  // reservations ne sont plus a jour.
  const status = booking.data?.status;
  useEffect(() => {
    if (status && isTerminal(status)) {
      void queryClient.invalidateQueries({ queryKey: eventKeys.all });
      void queryClient.invalidateQueries({ queryKey: bookingKeys.mine });
    }
  }, [status, queryClient]);

  if (booking.isLoading || !session.token) {
    return <Spinner label="Chargement de la réservation…" />;
  }
  if (booking.isError || !booking.data) {
    const notFound = booking.error instanceof ApiError && booking.error.status === 404;
    return (
      <Notice tone="error" title={notFound ? "Réservation introuvable" : "La réservation n'a pas pu être chargée"}>
        <p>
          {notFound
            ? "Elle n'existe pas, ou elle appartient à un autre compte."
            : errorMessage(booking.error)}
        </p>
        <Link to="/bookings" className="button button--ghost">
          Mes réservations
        </Link>
      </Notice>
    );
  }

  const current = booking.data;

  return (
    <>
      <Link to="/bookings" className="back-link">
        ← Mes réservations
      </Link>

      <div className="tracking">
        <section className="ticket">
          <div className="ticket__header">
            <div>
              <p className="ticket__eyebrow">Réservation {shortReference(current.id)}</p>
              <h1 className="ticket__title">{event.data?.title ?? "Événement"}</h1>
              {event.data && (
                <p className="ticket__meta">
                  {event.data.venue} · {formatDateTime(event.data.startsAt)}
                </p>
              )}
            </div>
            <StatusBadge status={current.status} />
          </div>
          <dl className="ticket__facts">
            <div>
              <dt>Places</dt>
              <dd>{pluralize(current.seatCount, "place")}</dd>
            </div>
            <div>
              <dt>Montant</dt>
              <dd>{formatPrice(current.totalAmount)}</dd>
            </div>
            <div>
              <dt>Demandée le</dt>
              <dd>{formatShortDateTime(current.createdAt)}</dd>
            </div>
          </dl>
        </section>

        <section className="card">
          <div className="section-heading">
            <h2>Suivi de la réservation</h2>
            {!isTerminal(current.status) && <span className="live">Mise à jour en direct</span>}
          </div>
          <SagaTimeline status={current.status} />
        </section>

        {current.status === "CONFIRMED" && (
          <Notice tone="success" title="C'est confirmé !">
            <p>
              Un e-mail de confirmation a été envoyé{session.email ? ` à ${session.email}` : ""}. En
              environnement de développement, il est visible dans{" "}
              <a href={MAILHOG_URL} target="_blank" rel="noreferrer">
                Mailhog
              </a>
              .
            </p>
          </Notice>
        )}
        {current.status === "CANCELLED" && (
          <Notice tone="warning" title="Cette réservation a été annulée">
            <p>Vos places ont été remises en vente et aucun montant n'a été débité.</p>
            {event.data && (
              <Link to={`/events/${event.data.id}`} className="button button--ghost">
                Réserver à nouveau
              </Link>
            )}
          </Notice>
        )}
      </div>
    </>
  );
}
