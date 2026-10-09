import { describe, expect, it } from "vitest";
import { decodeJwtPayload, rolesFromAccessToken } from "./token";

/** Fabrique un JWT non signe : seul le payload est lu cote navigateur. */
function tokenWith(payload: unknown): string {
  const encode = (value: unknown) =>
    btoa(String.fromCharCode(...new TextEncoder().encode(JSON.stringify(value))))
      .replace(/\+/g, "-")
      .replace(/\//g, "_")
      .replace(/=+$/, "");
  return `${encode({ alg: "RS256" })}.${encode(payload)}.signature`;
}

describe("rolesFromAccessToken", () => {
  it("lit les rôles realm d'un jeton Keycloak", () => {
    const token = tokenWith({ realm_access: { roles: ["ORGANIZER", "offline_access"] } });
    expect(rolesFromAccessToken(token)).toEqual(["ORGANIZER", "offline_access"]);
  });

  it("renvoie une liste vide sans jeton ou sans claim realm_access", () => {
    expect(rolesFromAccessToken(undefined)).toEqual([]);
    expect(rolesFromAccessToken(tokenWith({ sub: "abc" }))).toEqual([]);
  });

  it("ne plante pas sur un jeton malformé", () => {
    expect(rolesFromAccessToken("pas-un-jwt")).toEqual([]);
    expect(rolesFromAccessToken("a.%%%.c")).toEqual([]);
    expect(rolesFromAccessToken(tokenWith({ realm_access: { roles: "CUSTOMER" } }))).toEqual([]);
  });

  it("ignore les rôles qui ne sont pas des chaînes", () => {
    expect(rolesFromAccessToken(tokenWith({ realm_access: { roles: ["CUSTOMER", 42, null] } }))).toEqual([
      "CUSTOMER",
    ]);
  });
});

describe("decodeJwtPayload", () => {
  it("décode le base64url et les caractères accentués", () => {
    expect(decodeJwtPayload(tokenWith({ name: "Zoé Müller ??>>" }))).toEqual({ name: "Zoé Müller ??>>" });
  });
});
