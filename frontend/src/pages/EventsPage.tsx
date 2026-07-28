import { useQuery } from "@tanstack/react-query";

interface EventItem {
  id: string;
  title: string;
  venue: string;
  startsAt: string;
  remainingSeats: number;
}

async function fetchEvents(): Promise<EventItem[]> {
  const response = await fetch(`${import.meta.env.VITE_API_BASE_URL}/api/events`);
  if (!response.ok) {
    throw new Error("Impossible de recuperer les evenements");
  }
  return response.json();
}

export default function EventsPage() {
  const { data, isLoading, isError } = useQuery({
    queryKey: ["events"],
    queryFn: fetchEvents,
  });

  if (isLoading) return <p>Chargement des evenements...</p>;
  if (isError) return <p>Erreur lors du chargement des evenements.</p>;

  return (
    <div>
      <h2>Evenements a venir</h2>
      <ul>
        {data?.map((event) => (
          <li key={event.id} style={{ marginBottom: "0.75rem" }}>
            <strong>{event.title}</strong> — {event.venue}
            <br />
            {new Date(event.startsAt).toLocaleString("fr-FR")} · {event.remainingSeats} places restantes
            {/* TODO: bouton "Reserver" qui appelle POST /api/bookings (necessite d'etre connecte) */}
          </li>
        ))}
      </ul>
    </div>
  );
}
