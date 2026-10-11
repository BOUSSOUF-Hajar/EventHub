import { api } from "./client";
import type { Booking } from "../types";

export const bookingKeys = {
  mine: ["bookings", "me"] as const,
  one: (id: string) => ["bookings", id] as const,
};

export function createBooking(eventId: string, seatCount: number, token: string): Promise<Booking> {
  return api<Booking>("/api/bookings", { method: "POST", token, body: { eventId, seatCount } });
}

export function fetchMyBookings(token: string): Promise<Booking[]> {
  return api<Booking[]>("/api/bookings/me", { token });
}

export function fetchBooking(id: string, token: string): Promise<Booking> {
  return api<Booking>(`/api/bookings/${id}`, { token });
}
