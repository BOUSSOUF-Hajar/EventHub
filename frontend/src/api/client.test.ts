import { describe, expect, it } from "vitest";
import { messageFromErrorBody } from "./client";

describe("messageFromErrorBody", () => {
  it("reprend le détail métier renvoyé par le service (RFC 7807)", () => {
    const body = { status: 409, detail: "Plus assez de places disponibles pour l'evenement" };
    expect(messageFromErrorBody(409, body)).toBe("Plus assez de places disponibles pour l'evenement");
  });

  it("retombe sur un message par défaut quand le service ne dit rien", () => {
    expect(messageFromErrorBody(401, null)).toBe("Votre session a expiré, reconnectez-vous.");
    expect(messageFromErrorBody(403, {})).toContain("droits nécessaires");
    expect(messageFromErrorBody(503, { detail: "  " })).toContain("indisponible");
  });

  it("donne le code pour une erreur inattendue", () => {
    expect(messageFromErrorBody(500, { error: "Internal Server Error" })).toBe(
      "Une erreur est survenue (code 500).",
    );
  });
});
