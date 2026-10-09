import { describe, expect, it } from "vitest";
import { isTerminal, sagaSteps } from "./saga";
import type { BookingStatus } from "../types";

function states(status: BookingStatus) {
  return sagaSteps(status).map((step) => step.state);
}

describe("sagaSteps", () => {
  it("PENDING : places verrouillées, demande de paiement en cours de publication", () => {
    expect(states("PENDING")).toEqual(["done", "current", "upcoming", "upcoming"]);
  });

  it("AWAITING_PAYMENT : la demande est partie, le paiement est en cours", () => {
    expect(states("AWAITING_PAYMENT")).toEqual(["done", "done", "current", "upcoming"]);
  });

  it("CONFIRMED : toutes les étapes sont terminées", () => {
    expect(states("CONFIRMED")).toEqual(["done", "done", "done", "done"]);
    expect(sagaSteps("CONFIRMED")[3].title).toBe("Réservation confirmée");
  });

  it("CANCELLED : le paiement est en échec et la dernière étape montre la compensation", () => {
    expect(states("CANCELLED")).toEqual(["done", "done", "failed", "compensated"]);
    const outcome = sagaSteps("CANCELLED")[3];
    expect(outcome.title).toContain("places restituées");
    expect(outcome.component).toBe("Saga : compensation");
  });

  it("garde toujours les quatre mêmes étapes, dans le même ordre", () => {
    for (const status of ["PENDING", "AWAITING_PAYMENT", "CONFIRMED", "CANCELLED"] as const) {
      expect(sagaSteps(status).map((step) => step.key)).toEqual(["lock", "request", "payment", "outcome"]);
    }
  });
});

describe("isTerminal", () => {
  it("seuls CONFIRMED et CANCELLED arrêtent le suivi en direct", () => {
    expect(isTerminal("PENDING")).toBe(false);
    expect(isTerminal("AWAITING_PAYMENT")).toBe(false);
    expect(isTerminal("CONFIRMED")).toBe(true);
    expect(isTerminal("CANCELLED")).toBe(true);
  });
});
