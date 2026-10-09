import { useQuery } from "@tanstack/react-query";
import { eventKeys, fetchEvents } from "../api/events";
import { errorMessage } from "../api/client";
import { useSession } from "../auth/useSession";
import EventCard from "../components/EventCard";
import { EmptyState, Notice, Spinner } from "../components/Feedback";

/** FE-1 : catalogue public, consultable sans connexion. */
export default function EventsPage() {
  const session = useSession();
  const events = useQuery({ queryKey: eventKeys.all, queryFn: fetchEvents });

  const now = Date.now();
  const sorted = [...(events.data ?? [])].sort(
    (a, b) => new Date(a.startsAt).getTime() - new Date(b.startsAt).getTime(),
  );
  const upcoming = sorted.filter((event) => new Date(event.startsAt).getTime() > now);
  const past = sorted.filter((event) => new Date(event.startsAt).getTime() <= now).reverse();

  return (
    <>
      <section className="hero">
        <h1 className="hero__title">Réservez vos places, sans mauvaise surprise.</h1>
        <p className="hero__text">
          Concerts, conférences, matchs : parcourez les événements à venir, réservez en quelques secondes et
          suivez votre réservation jusqu'à sa confirmation.
        </p>
      </section>

      {!session.isLoading && !session.isAuthenticated && <DemoAccounts onLogin={() => session.login()} />}

      <section>
        <div className="section-heading">
          <h2>Événements à venir</h2>
          {events.data && <span className="section-heading__count">{upcoming.length}</span>}
        </div>

        {events.isLoading && <Spinner label="Chargement des événements…" />}
        {events.isError && (
          <Notice tone="error" title="Le catalogue n'a pas pu être chargé">
            <p>{errorMessage(events.error)}</p>
            <button className="button button--ghost" onClick={() => void events.refetch()}>
              Réessayer
            </button>
          </Notice>
        )}
        {events.data && upcoming.length === 0 && (
          <EmptyState title="Aucun événement à venir pour le moment">
            <p>Connectez-vous avec un compte organisateur pour en publier un.</p>
          </EmptyState>
        )}
        <div className="event-list">
          {upcoming.map((event) => (
            <EventCard key={event.id} event={event} />
          ))}
        </div>
      </section>

      {past.length > 0 && (
        <section>
          <div className="section-heading">
            <h2>Événements passés</h2>
            <span className="section-heading__count">{past.length}</span>
          </div>
          <div className="event-list event-list--muted">
            {past.map((event) => (
              <EventCard key={event.id} event={event} />
            ))}
          </div>
        </section>
      )}
    </>
  );
}

/** Aide a la demonstration : les comptes du realm Keycloak importe en local. */
function DemoAccounts({ onLogin }: { onLogin: () => void }) {
  return (
    <aside className="demo" aria-label="Comptes de démonstration">
      <div>
        <p className="demo__title">Comptes de démonstration</p>
        <p className="demo__text">
          Le catalogue est public. Connectez-vous pour réserver ou publier un événement — mot de passe :{" "}
          <code>password</code>.
        </p>
        <ul className="demo__accounts">
          <li>
            <code>alice.customer</code> <span>Cliente : réserve des places et suit ses réservations</span>
          </li>
          <li>
            <code>bob.organizer</code> <span>Organisateur : crée et supprime des événements</span>
          </li>
        </ul>
      </div>
      <button className="button button--primary" onClick={onLogin}>
        Se connecter
      </button>
    </aside>
  );
}
