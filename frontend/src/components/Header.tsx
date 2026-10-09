import { Link, NavLink } from "react-router-dom";
import { useSession } from "../auth/useSession";

const ROLE_LABELS: Record<string, string> = {
  CUSTOMER: "Client",
  ORGANIZER: "Organisateur",
  ADMIN: "Administrateur",
};

export default function Header() {
  const session = useSession();
  const businessRoles = session.roles.filter((role) => role in ROLE_LABELS);

  return (
    <header className="header">
      <div className="header__inner">
        <Link to="/" className="brand">
          <span className="brand__mark" aria-hidden="true" />
          EventHub
        </Link>

        <nav className="nav" aria-label="Navigation principale">
          <NavLink to="/" end className="nav__link">
            Événements
          </NavLink>
          {session.isAuthenticated && session.canBook && (
            <NavLink to="/bookings" className="nav__link">
              Mes réservations
            </NavLink>
          )}
          {/* FE-4 : l'entree n'apparait que si le role figure dans le jeton */}
          {session.isAuthenticated && session.canManageEvents && (
            <NavLink to="/organizer" className="nav__link">
              Espace organisateur
            </NavLink>
          )}
        </nav>

        <div className="account">
          {session.isLoading ? null : session.isAuthenticated ? (
            <>
              <div className="account__identity">
                <span className="account__name">{session.username}</span>
                <span className="account__roles">
                  {businessRoles.length > 0
                    ? businessRoles.map((role) => ROLE_LABELS[role]).join(" · ")
                    : "Aucun rôle"}
                </span>
              </div>
              <button className="button button--ghost" onClick={session.logout}>
                Se déconnecter
              </button>
            </>
          ) : (
            <button className="button button--primary" onClick={() => session.login()}>
              Se connecter
            </button>
          )}
        </div>
      </div>
    </header>
  );
}
