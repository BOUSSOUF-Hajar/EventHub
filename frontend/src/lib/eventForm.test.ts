import { describe, expect, it } from "vitest";
import { validateEventForm, type EventFormValues } from "./eventForm";

const NOW = new Date("2026-10-09T12:00:00");

const VALID: EventFormValues = {
  title: "  Concert Jazz ",
  description: " Soirée en plein air ",
  venue: "Rabat",
  startsAt: "2027-01-15T20:00",
  totalCapacity: "200",
  unitPrice: "35,5",
};

describe("validateEventForm", () => {
  it("convertit un formulaire valide en événement prêt à envoyer", () => {
    const result = validateEventForm(VALID, NOW);

    expect(result.ok).toBe(true);
    if (result.ok) {
      expect(result.event).toEqual({
        title: "Concert Jazz",
        description: "Soirée en plein air",
        venue: "Rabat",
        startsAt: new Date("2027-01-15T20:00").toISOString(),
        totalCapacity: 200,
        unitPrice: 35.5,
      });
    }
  });

  it("signale tous les champs obligatoires manquants d'un coup", () => {
    const result = validateEventForm(
      { title: " ", description: "", venue: "", startsAt: "", totalCapacity: "", unitPrice: "" },
      NOW,
    );

    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(Object.keys(result.errors).sort()).toEqual(
        ["startsAt", "title", "totalCapacity", "unitPrice", "venue"].sort(),
      );
    }
  });

  it("refuse une date passée", () => {
    const result = validateEventForm({ ...VALID, startsAt: "2026-10-09T11:00" }, NOW);
    expect(result.ok ? null : result.errors.startsAt).toBe("La date doit être dans le futur.");
  });

  it.each(["0", "-5", "12.5", "abc"])("refuse la capacité « %s »", (totalCapacity) => {
    const result = validateEventForm({ ...VALID, totalCapacity }, NOW);
    expect(result.ok ? null : result.errors.totalCapacity).toBeDefined();
  });

  it.each(["0", "-1", "gratuit"])("refuse le prix « %s » : pas de réservation gratuite", (unitPrice) => {
    const result = validateEventForm({ ...VALID, unitPrice }, NOW);
    expect(result.ok ? null : result.errors.unitPrice).toBe("Le prix doit être supérieur à 0.");
  });

  it("arrondit le prix au centime", () => {
    const result = validateEventForm({ ...VALID, unitPrice: "19.999" }, NOW);
    expect(result.ok ? result.event.unitPrice : null).toBe(20);
  });
});
