import { Link } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { bookingKeys, fetchMyBookings } from "../api/bookings";
import { eventKeys, fetchEvents } from "../api/events";
import { errorMessage } from "../api/client";
import { useSession } from "../auth/useSession";
import { EmptyState, Notice, Spinner } from "../components/Feedback";
import StatusBadge from "../components/StatusBadge";
import { formatPrice, formatShortDateTime, pluralize, shortReference } from "../lib/format";
import { isTerminal } from "../lib/saga";

const POLL_INTERVAL_MS = 2000;

/** BKG-6 cote interface : les reservations du compte connecte, et seulement celles-la. */
export default function MyBookingsPage() {
  const session = useSession();

  const bookings = useQuery({
    queryKey: bookingKeys.mine,
    queryFn: () => fetchMyBookings(session.token ?? ""),
    enabled: Boolean(session.token),
    // Rafraichissement automatique tant qu'au moins une reservation est en cours.
    refetchInterval: (query) =>
      query.state.data?.some((booking) => !isTerminal(booking.status)) ? POLL_INTERVAL_MS : false,
  });

  // Une reservation ne porte que l'identifiant de l'evenement : son titre vient du catalogue.
  const events = useQuery({ queryKey: eventKeys.all, queryFn: fetchEvents });
  const titles = new Map(events.data?.map((event) => [event.id, event.title]));

  return (
    <>
      <div className="page-heading">
        <h1>Mes réservations</h1>
        <button
          className="button button--ghost"
          onClick={() => void bookings.refetch()}
          disabled={bookings.isFetching}
        >
          {bookings.isFetching ? "Actualisation…" : "Actualiser"}
        </button>
      </div>

      {bookings.isLoading && <Spinner label="Chargement de vos réservations…" />}
      {bookings.isError && (
        <Notice tone="error" title="Vos réservations n'ont pas pu être chargées">
          <p>{errorMessage(bookings.error)}</p>
        </Notice>
      )}
      {bookings.data?.length === 0 && (
        <EmptyState title="Vous n'avez encore aucune réservation">
          <Link to="/" className="button button--primary">
            Parcourir les événements
          </Link>
        </EmptyState>
      )}

      <ul className="booking-list">
        {bookings.data?.map((booking) => (
          <li key={booking.id}>
            <Link to={`/bookings/${booking.id}`} className="booking-row">
              <div className="booking-row__main">
                <span className="booking-row__title">
                  {titles.get(booking.eventId) ?? (events.isLoading ? "…" : "Événement supprimé")}
                </span>
                <span className="booking-row__meta">
                  Réf. {shortReference(booking.id)} · {formatShortDateTime(booking.createdAt)}
                </span>
              </div>
              <span className="booking-row__seats">{pluralize(booking.seatCount, "place")}</span>
              <span className="booking-row__amount">{formatPrice(booking.totalAmount)}</span>
              <StatusBadge status={booking.status} />
            </Link>
          </li>
        ))}
      </ul>
    </>
  );
}
