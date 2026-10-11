import type { NewEvent } from "../types";

/** Valeurs brutes du formulaire : tout est une chaine tant que ce n'est pas valide. */
export interface EventFormValues {
  title: string;
  description: string;
  venue: string;
  startsAt: string;
  totalCapacity: string;
  unitPrice: string;
}

export type EventFormErrors = Partial<Record<keyof EventFormValues, string>>;

export type EventFormValidation =
  | { ok: true; event: NewEvent }
  | { ok: false; errors: EventFormErrors };

/**
 * Validation cote navigateur, pour guider la saisie. Elle reprend les regles de
 * l'event-service (champs obligatoires, prix strictement positif) et y ajoute ce qu'il
 * ne verifie pas encore : une date future et une capacite d'au moins une place.
 */
export function validateEventForm(values: EventFormValues, now: Date = new Date()): EventFormValidation {
  const errors: EventFormErrors = {};

  const title = values.title.trim();
  if (title === "") {
    errors.title = "Le titre est obligatoire.";
  }

  const venue = values.venue.trim();
  if (venue === "") {
    errors.venue = "Le lieu est obligatoire.";
  }

  // datetime-local donne une heure locale sans fuseau : new Date() l'interprete comme telle.
  const startsAt = new Date(values.startsAt);
  if (values.startsAt === "" || Number.isNaN(startsAt.getTime())) {
    errors.startsAt = "La date est obligatoire.";
  } else if (startsAt.getTime() <= now.getTime()) {
    errors.startsAt = "La date doit être dans le futur.";
  }

  const totalCapacity = Number(values.totalCapacity);
  if (values.totalCapacity.trim() === "" || !Number.isInteger(totalCapacity) || totalCapacity < 1) {
    errors.totalCapacity = "La capacité doit être un nombre entier d'au moins 1.";
  }

  const unitPrice = Number(values.unitPrice.replace(",", "."));
  if (values.unitPrice.trim() === "" || !Number.isFinite(unitPrice) || unitPrice <= 0) {
    errors.unitPrice = "Le prix doit être supérieur à 0.";
  }

  if (Object.keys(errors).length > 0) {
    return { ok: false, errors };
  }
  return {
    ok: true,
    event: {
      title,
      description: values.description.trim(),
      venue,
      startsAt: startsAt.toISOString(),
      totalCapacity,
      unitPrice: Math.round(unitPrice * 100) / 100,
    },
  };
}
