import { api } from "./client";
import type { EventItem, NewEvent } from "../types";

export const eventKeys = {
  all: ["events"] as const,
  one: (id: string) => ["events", id] as const,
};

export function fetchEvents(): Promise<EventItem[]> {
  return api<EventItem[]>("/api/events");
}

export function fetchEvent(id: string): Promise<EventItem> {
  return api<EventItem>(`/api/events/${id}`);
}

export function createEvent(event: NewEvent, token: string): Promise<EventItem> {
  // A la creation, toutes les places sont disponibles.
  return api<EventItem>("/api/events", {
    method: "POST",
    token,
    body: { ...event, remainingSeats: event.totalCapacity },
  });
}

export function deleteEvent(id: string, token: string): Promise<void> {
  return api<void>(`/api/events/${id}`, { method: "DELETE", token });
}
