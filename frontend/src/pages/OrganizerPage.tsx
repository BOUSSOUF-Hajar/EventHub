import { useState, type FormEvent, type ReactNode } from "react";
import { Link } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createEvent, deleteEvent, eventKeys, fetchEvents } from "../api/events";
import { errorMessage } from "../api/client";
import { useSession } from "../auth/useSession";
import { EmptyState, Notice, Spinner } from "../components/Feedback";
import { formatPrice, formatShortDateTime } from "../lib/format";
import { validateEventForm, type EventFormValues } from "../lib/eventForm";
import type { EventItem, NewEvent } from "../types";

const EMPTY_FORM: EventFormValues = {
  title: "",
  description: "",
  venue: "",
  startsAt: "",
  totalCapacity: "",
  unitPrice: "",
};

/** FE-4 : ecran organisateur — creation (EVT-3) et suppression (EVT-4) d'evenements. */
export default function OrganizerPage() {
  const session = useSession();
  const queryClient = useQueryClient();
  const events = useQuery({ queryKey: eventKeys.all, queryFn: fetchEvents });

  const [form, setForm] = useState<EventFormValues>(EMPTY_FORM);
  const [errors, setErrors] = useState<Partial<Record<keyof EventFormValues, string>>>({});
  const [created, setCreated] = useState<EventItem | null>(null);

  const creation = useMutation({
    mutationFn: (event: NewEvent) => createEvent(event, session.token ?? ""),
    onSuccess: (event) => {
      setCreated(event);
      setForm(EMPTY_FORM);
      void queryClient.invalidateQueries({ queryKey: eventKeys.all });
    },
  });

  const removal = useMutation({
    mutationFn: (id: string) => deleteEvent(id, session.token ?? ""),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: eventKeys.all });
    },
  });

  function update(field: keyof EventFormValues, value: string) {
    setForm((current) => ({ ...current, [field]: value }));
    setErrors((current) => ({ ...current, [field]: undefined }));
  }

  function submit(submission: FormEvent) {
    submission.preventDefault();
    setCreated(null);
    const validation = validateEventForm(form);
    if (!validation.ok) {
      setErrors(validation.errors);
      return;
    }
    setErrors({});
    creation.mutate(validation.event);
  }

  function remove(event: EventItem) {
    if (window.confirm(`Supprimer définitivement « ${event.title} » ?`)) {
      removal.mutate(event.id);
    }
  }

  const sorted = [...(events.data ?? [])].sort(
    (a, b) => new Date(a.startsAt).getTime() - new Date(b.startsAt).getTime(),
  );

  return (
    <>
      <div className="page-heading">
        <h1>Espace organisateur</h1>
      </div>

      <div className="organizer">
        <section className="card">
          <h2>Publier un événement</h2>
          <form className="form" onSubmit={submit} noValidate>
            <Field label="Titre" error={errors.title}>
              <input
                value={form.title}
                onChange={(input) => update("title", input.target.value)}
                placeholder="Concert Jazz"
                maxLength={255}
              />
            </Field>
            <Field label="Lieu" error={errors.venue}>
              <input
                value={form.venue}
                onChange={(input) => update("venue", input.target.value)}
                placeholder="Théâtre Mohammed V, Rabat"
                maxLength={255}
              />
            </Field>
            <Field label="Date et heure" error={errors.startsAt}>
              <input
                type="datetime-local"
                value={form.startsAt}
                onChange={(input) => update("startsAt", input.target.value)}
              />
            </Field>
            <div className="form__row">
              <Field label="Capacité totale" error={errors.totalCapacity}>
                <input
                  type="number"
                  min={1}
                  step={1}
                  inputMode="numeric"
                  value={form.totalCapacity}
                  onChange={(input) => update("totalCapacity", input.target.value)}
                  placeholder="200"
                />
              </Field>
              <Field label="Prix de la place (€)" error={errors.unitPrice}>
                <input
                  type="number"
                  min={0.01}
                  step={0.01}
                  inputMode="decimal"
                  value={form.unitPrice}
                  onChange={(input) => update("unitPrice", input.target.value)}
                  placeholder="35.00"
                />
              </Field>
            </div>
            <Field label="Description (facultative)" error={errors.description}>
              <textarea
                rows={3}
                value={form.description}
                onChange={(input) => update("description", input.target.value)}
                maxLength={255}
              />
            </Field>

            {creation.isError && (
              <Notice tone="error" title="L'événement n'a pas pu être publié">
                <p>{errorMessage(creation.error)}</p>
              </Notice>
            )}
            {created && (
              <Notice tone="success" title="Événement publié">
                <p>
                  « {created.title} » est en ligne. <Link to={`/events/${created.id}`}>Voir la page</Link>
                </p>
              </Notice>
            )}

            <button className="button button--primary" type="submit" disabled={creation.isPending}>
              {creation.isPending ? "Publication…" : "Publier l'événement"}
            </button>
          </form>
        </section>

        <section className="card">
          <div className="section-heading">
            <h2>Événements publiés</h2>
            {events.data && <span className="section-heading__count">{events.data.length}</span>}
          </div>

          {events.isLoading && <Spinner label="Chargement…" />}
          {events.isError && (
            <Notice tone="error">
              <p>{errorMessage(events.error)}</p>
            </Notice>
          )}
          {removal.isError && (
            <Notice tone="error" title="La suppression a échoué">
              <p>{errorMessage(removal.error)}</p>
            </Notice>
          )}
          {events.data?.length === 0 && <EmptyState title="Aucun événement publié" />}

          <ul className="managed-list">
            {sorted.map((event) => (
              <li key={event.id} className="managed">
                <div className="managed__main">
                  <Link to={`/events/${event.id}`} className="managed__title">
                    {event.title}
                  </Link>
                  <span className="managed__meta">
                    {formatShortDateTime(event.startsAt)} · {event.venue} · {formatPrice(event.unitPrice)} ·{" "}
                    {event.remainingSeats}/{event.totalCapacity} places
                  </span>
                </div>
                <button
                  className="button button--danger"
                  onClick={() => remove(event)}
                  disabled={removal.isPending && removal.variables === event.id}
                >
                  Supprimer
                </button>
              </li>
            ))}
          </ul>
        </section>
      </div>
    </>
  );
}

function Field({ label, error, children }: { label: string; error?: string; children: ReactNode }) {
  return (
    <label className={`field${error ? " field--invalid" : ""}`}>
      <span className="field__label">{label}</span>
      {children}
      {error && <span className="field__error">{error}</span>}
    </label>
  );
}
