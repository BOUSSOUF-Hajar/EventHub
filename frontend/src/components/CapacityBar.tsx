import type { EventItem } from "../types";
import { availabilityOf, pluralize, soldPercentage } from "../lib/format";

const LABELS = {
  past: "Événement terminé",
  "sold-out": "Complet",
  "last-seats": "Dernières places",
  available: "Disponible",
} as const;

/** Jauge de remplissage : rend visible la capacite limitee, raison d'etre du verrou de places. */
export default function CapacityBar({ event }: { event: EventItem }) {
  const availability = availabilityOf(event);
  const sold = soldPercentage(event);

  return (
    <div className={`capacity capacity--${availability}`}>
      <div className="capacity__header">
        <span className="capacity__state">{LABELS[availability]}</span>
        <span className="capacity__count">
          {pluralize(event.remainingSeats, "place restante", "places restantes")} sur {event.totalCapacity}
        </span>
      </div>
      <div
        className="capacity__track"
        role="progressbar"
        aria-label="Taux de remplissage"
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={sold}
      >
        <div className="capacity__fill" style={{ width: `${sold}%` }} />
      </div>
    </div>
  );
}
