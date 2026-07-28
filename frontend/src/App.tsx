import { Routes, Route } from "react-router-dom";
import { useAuth } from "react-oidc-context";
import EventsPage from "./pages/EventsPage";

export default function App() {
  const auth = useAuth();

  return (
    <div style={{ fontFamily: "sans-serif", padding: "1.5rem" }}>
      <header style={{ display: "flex", justifyContent: "space-between", marginBottom: "1.5rem" }}>
        <h1>EventHub</h1>
        {auth.isAuthenticated ? (
          <button onClick={() => auth.removeUser()}>
            Se deconnecter ({auth.user?.profile.preferred_username})
          </button>
        ) : (
          <button onClick={() => auth.signinRedirect()}>Se connecter</button>
        )}
      </header>

      {/* TODO: ajouter les routes /bookings (parcours de reservation, protegee)
          et /admin/events (creation d'evenement, role ORGANIZER requis) */}
      <Routes>
        <Route path="/" element={<EventsPage />} />
      </Routes>
    </div>
  );
}
