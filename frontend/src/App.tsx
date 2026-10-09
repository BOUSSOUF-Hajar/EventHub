import { Link, Route, Routes } from "react-router-dom";
import { useAuth } from "react-oidc-context";
import Protected from "./auth/Protected";
import Header from "./components/Header";
import { EmptyState, Notice } from "./components/Feedback";
import BookingPage from "./pages/BookingPage";
import EventDetailPage from "./pages/EventDetailPage";
import EventsPage from "./pages/EventsPage";
import MyBookingsPage from "./pages/MyBookingsPage";
import OrganizerPage from "./pages/OrganizerPage";

export default function App() {
  const auth = useAuth();

  return (
    <>
      <Header />
      <main className="main">
        {auth.error && (
          <Notice tone="error" title="La connexion a échoué">
            <p>{auth.error.message}. Vérifiez que Keycloak est démarré, puis réessayez.</p>
          </Notice>
        )}
        <Routes>
          {/* Public (FE-1) */}
          <Route path="/" element={<EventsPage />} />
          <Route path="/events/:eventId" element={<EventDetailPage />} />

          {/* Client connecte (FE-3) */}
          <Route
            path="/bookings"
            element={
              <Protected require="booking">
                <MyBookingsPage />
              </Protected>
            }
          />
          <Route
            path="/bookings/:bookingId"
            element={
              <Protected>
                <BookingPage />
              </Protected>
            }
          />

          {/* Organisateur (FE-4) */}
          <Route
            path="/organizer"
            element={
              <Protected require="event-management">
                <OrganizerPage />
              </Protected>
            }
          />

          <Route
            path="*"
            element={
              <EmptyState title="Cette page n'existe pas">
                <Link to="/" className="button button--primary">
                  Retour aux événements
                </Link>
              </EmptyState>
            }
          />
        </Routes>
      </main>
      <footer className="footer">
        EventHub — projet de démonstration d'une architecture microservices. Les paiements sont simulés.
      </footer>
    </>
  );
}
