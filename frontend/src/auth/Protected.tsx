import type { ReactNode } from "react";
import { useSession } from "./useSession";
import { Notice, Spinner } from "../components/Feedback";

interface ProtectedProps {
  /** Role exige en plus de la connexion. */
  require?: "booking" | "event-management";
  children: ReactNode;
}

/**
 * Garde d'affichage d'une page. Ce n'est qu'un confort d'interface : chaque service
 * revérifie le JWT et les roles, un appel direct a l'API serait refuse de la meme facon.
 */
export default function Protected({ require, children }: ProtectedProps) {
  const session = useSession();

  if (session.isLoading) {
    return <Spinner label="Vérification de la session…" />;
  }

  if (!session.isAuthenticated) {
    return (
      <Notice tone="info" title="Connexion requise">
        <p>Cette page est réservée aux utilisateurs connectés.</p>
        <button className="button button--primary" onClick={() => session.login()}>
          Se connecter
        </button>
      </Notice>
    );
  }

  const allowed =
    require === "event-management" ? session.canManageEvents : require === "booking" ? session.canBook : true;

  if (!allowed) {
    return (
      <Notice tone="warning" title="Accès réservé">
        <p>
          {require === "event-management"
            ? "Cet écran est réservé aux organisateurs."
            : "Cet écran est réservé aux clients."}{" "}
          Vous êtes connecté en tant que <strong>{session.username}</strong>
          {session.roles.length > 0 ? ` (${session.roles.filter(isBusinessRole).join(", ") || "aucun rôle métier"})` : ""}.
        </p>
      </Notice>
    );
  }

  return <>{children}</>;
}

function isBusinessRole(role: string): boolean {
  return role === "CUSTOMER" || role === "ORGANIZER" || role === "ADMIN";
}
