const priceFormat = new Intl.NumberFormat("fr-FR", { style: "currency", currency: "EUR" });

const dateTimeFormat = new Intl.DateTimeFormat("fr-FR", {
  weekday: "long",
  day: "numeric",
  month: "long",
  year: "numeric",
  hour: "2-digit",
  minute: "2-digit",
});

const shortDateTimeFormat = new Intl.DateTimeFormat("fr-FR", {
  day: "2-digit",
  month: "2-digit",
  year: "numeric",
  hour: "2-digit",
  minute: "2-digit",
});

export function formatPrice(amount: number): string {
  return priceFormat.format(amount);
}

export function formatDateTime(iso: string): string {
  return dateTimeFormat.format(new Date(iso));
}

export function formatShortDateTime(iso: string): string {
  return shortDateTimeFormat.format(new Date(iso));
}

export function dayOfMonth(iso: string): string {
  return new Intl.DateTimeFormat("fr-FR", { day: "2-digit" }).format(new Date(iso));
}

export function shortMonth(iso: string): string {
  return new Intl.DateTimeFormat("fr-FR", { month: "short" }).format(new Date(iso)).replace(".", "");
}

export function pluralize(count: number, singular: string, plural = `${singular}s`): string {
  return `${count} ${count > 1 ? plural : singular}`;
}

/** Les identifiants sont des UUID : on n'en montre que le debut, comme une reference de billet. */
export function shortReference(id: string): string {
  return id.slice(0, 8).toUpperCase();
}

export type Availability = "past" | "sold-out" | "last-seats" | "available";

/**
 * Etat de disponibilite affiche sur un evenement. "Dernieres places" des qu'il en
 * reste 10 % ou moins : c'est la situation de ruee que le verrou Redis arbitre.
 */
export function availabilityOf(
  event: { startsAt: string; totalCapacity: number; remainingSeats: number },
  now: Date = new Date(),
): Availability {
  if (new Date(event.startsAt).getTime() <= now.getTime()) {
    return "past";
  }
  if (event.remainingSeats <= 0) {
    return "sold-out";
  }
  if (event.remainingSeats <= Math.max(1, Math.floor(event.totalCapacity * 0.1))) {
    return "last-seats";
  }
  return "available";
}

/** Part des places deja vendues, bornee a [0, 100] pour la jauge. */
export function soldPercentage(event: { totalCapacity: number; remainingSeats: number }): number {
  if (event.totalCapacity <= 0) {
    return 100;
  }
  const sold = event.totalCapacity - event.remainingSeats;
  return Math.min(100, Math.max(0, Math.round((sold / event.totalCapacity) * 100)));
}
