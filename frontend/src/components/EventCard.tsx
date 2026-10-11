import { Link } from "react-router-dom";
import type { EventItem } from "../types";
import { dayOfMonth, formatDateTime, formatPrice, shortMonth } from "../lib/format";
import CapacityBar from "./CapacityBar";

export default function EventCard({ event }: { event: EventItem }) {
  return (
    <Link to={`/events/${event.id}`} className="event-card">
      <div className="event-card__date" aria-hidden="true">
        <span className="event-card__day">{dayOfMonth(event.startsAt)}</span>
        <span className="event-card__month">{shortMonth(event.startsAt)}</span>
      </div>
      <div className="event-card__body">
        <h3 className="event-card__title">{event.title}</h3>
        <p className="event-card__meta">
          {event.venue} · {formatDateTime(event.startsAt)}
        </p>
        <CapacityBar event={event} />
      </div>
      <div className="event-card__price">
        <span className="event-card__amount">{formatPrice(event.unitPrice)}</span>
        <span className="event-card__unit">la place</span>
      </div>
    </Link>
  );
}
