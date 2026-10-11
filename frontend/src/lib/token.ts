/**
 * Lecture des roles dans le jeton d'acces Keycloak (claim "realm_access.roles").
 *
 * Le jeton n'est PAS verifie ici, et n'a pas a l'etre : ces roles ne servent qu'a
 * choisir quoi afficher (FE-4). L'autorisation reelle est faite par chaque service,
 * qui valide la signature du JWT. Masquer un bouton n'est pas une mesure de securite.
 */
export function decodeJwtPayload(token: string): Record<string, unknown> | null {
  const parts = token.split(".");
  if (parts.length !== 3) {
    return null;
  }
  try {
    const base64 = parts[1].replace(/-/g, "+").replace(/_/g, "/");
    const padded = base64.padEnd(Math.ceil(base64.length / 4) * 4, "=");
    const bytes = Uint8Array.from(atob(padded), (char) => char.charCodeAt(0));
    const payload: unknown = JSON.parse(new TextDecoder().decode(bytes));
    return payload && typeof payload === "object" ? (payload as Record<string, unknown>) : null;
  } catch {
    return null;
  }
}

export function rolesFromAccessToken(token: string | undefined): string[] {
  if (!token) {
    return [];
  }
  const realmAccess = decodeJwtPayload(token)?.realm_access;
  if (!realmAccess || typeof realmAccess !== "object") {
    return [];
  }
  const roles = (realmAccess as { roles?: unknown }).roles;
  return Array.isArray(roles) ? roles.filter((role): role is string => typeof role === "string") : [];
}
