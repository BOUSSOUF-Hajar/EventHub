const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080";

/** Erreur d'API portant un message deja presentable a l'utilisateur. */
export class ApiError extends Error {
  constructor(
    public readonly status: number,
    message: string,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

interface RequestOptions {
  method?: "GET" | "POST" | "DELETE";
  body?: unknown;
  /** Jeton d'acces Keycloak ; absent pour les appels publics (catalogue). */
  token?: string;
}

/**
 * Message par defaut selon le code HTTP. Les services renvoient un "detail" (RFC 7807)
 * pour les erreurs metier ; ces textes ne servent que lorsqu'il n'y en a pas.
 */
export function defaultMessageFor(status: number): string {
  switch (status) {
    case 400:
      return "Les données envoyées sont invalides.";
    case 401:
      return "Votre session a expiré, reconnectez-vous.";
    case 403:
      return "Votre compte n'a pas les droits nécessaires pour cette action.";
    case 404:
      return "Élément introuvable.";
    case 409:
      return "L'opération est en conflit avec l'état actuel.";
    case 503:
      return "Le service est momentanément indisponible, réessayez dans un instant.";
    default:
      return `Une erreur est survenue (code ${status}).`;
  }
}

/** Extrait le message d'erreur d'une reponse : le "detail" du service s'il existe. */
export function messageFromErrorBody(status: number, body: unknown): string {
  if (body && typeof body === "object" && "detail" in body) {
    const detail = (body as { detail: unknown }).detail;
    if (typeof detail === "string" && detail.trim() !== "") {
      return detail;
    }
  }
  return defaultMessageFor(status);
}

export async function api<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const headers: Record<string, string> = { Accept: "application/json" };
  if (options.body !== undefined) {
    headers["Content-Type"] = "application/json";
  }
  if (options.token) {
    headers.Authorization = `Bearer ${options.token}`;
  }

  let response: Response;
  try {
    response = await fetch(`${BASE_URL}${path}`, {
      method: options.method ?? "GET",
      headers,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
    });
  } catch {
    // fetch ne rejette que sur une panne reseau (ou un refus CORS) : le Gateway est injoignable
    throw new ApiError(0, "Le serveur est injoignable. Vérifiez que le Gateway est démarré.");
  }

  if (!response.ok) {
    const body = await response.json().catch(() => null);
    throw new ApiError(response.status, messageFromErrorBody(response.status, body));
  }
  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}

export function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : "Une erreur inattendue est survenue.";
}
