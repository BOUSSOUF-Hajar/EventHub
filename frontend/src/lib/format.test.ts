import { describe, expect, it } from "vitest";
import { availabilityOf, pluralize, shortReference, soldPercentage } from "./format";

const NOW = new Date("2026-10-09T12:00:00Z");
const FUTURE = "2027-01-15T20:00:00Z";

describe("availabilityOf", () => {
  it("disponible tant qu'il reste plus de 10 % des places", () => {
    expect(availabilityOf({ startsAt: FUTURE, totalCapacity: 200, remainingSeats: 21 }, NOW)).toBe("available");
  });

  it("dernières places à 10 % ou moins", () => {
    expect(availabilityOf({ startsAt: FUTURE, totalCapacity: 200, remainingSeats: 20 }, NOW)).toBe("last-seats");
  });

  it("une petite salle affiche « dernières places » pour sa toute dernière place", () => {
    expect(availabilityOf({ startsAt: FUTURE, totalCapacity: 5, remainingSeats: 1 }, NOW)).toBe("last-seats");
    expect(availabilityOf({ startsAt: FUTURE, totalCapacity: 5, remainingSeats: 2 }, NOW)).toBe("available");
  });

  it("complet quand il ne reste rien", () => {
    expect(availabilityOf({ startsAt: FUTURE, totalCapacity: 200, remainingSeats: 0 }, NOW)).toBe("sold-out");
  });

  it("un événement déjà commencé est passé, même s'il reste des places", () => {
    expect(availabilityOf({ startsAt: "2026-10-09T11:59:00Z", totalCapacity: 200, remainingSeats: 50 }, NOW)).toBe(
      "past",
    );
  });
});

describe("soldPercentage", () => {
  it("calcule la part vendue", () => {
    expect(soldPercentage({ totalCapacity: 200, remainingSeats: 150 })).toBe(25);
    expect(soldPercentage({ totalCapacity: 200, remainingSeats: 0 })).toBe(100);
  });

  it("reste dans [0, 100] sur des données incohérentes", () => {
    expect(soldPercentage({ totalCapacity: 10, remainingSeats: 15 })).toBe(0);
    expect(soldPercentage({ totalCapacity: 0, remainingSeats: 0 })).toBe(100);
  });
});

describe("pluralize", () => {
  it("accorde au pluriel à partir de 2", () => {
    expect(pluralize(1, "place")).toBe("1 place");
    expect(pluralize(2, "place")).toBe("2 places");
    expect(pluralize(0, "place restante", "places restantes")).toBe("0 place restante");
  });
});

describe("shortReference", () => {
  it("garde le début de l'UUID en majuscules", () => {
    expect(shortReference("63930255-a5ec-4fde-b93e-65d3434e999d")).toBe("63930255");
  });
});
