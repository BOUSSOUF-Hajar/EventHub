export interface EventItem {
  id: string;
  title: string;
  description: string | null;
  venue: string;
  startsAt: string;
  totalCapacity: number;
  remainingSeats: number;
  unitPrice: number;
}

export interface NewEvent {
  title: string;
  description: string;
  venue: string;
  startsAt: string;
  totalCapacity: number;
  unitPrice: number;
}

export type BookingStatus = "PENDING" | "AWAITING_PAYMENT" | "CONFIRMED" | "CANCELLED";

export interface Booking {
  id: string;
  eventId: string;
  seatCount: number;
  totalAmount: number;
  status: BookingStatus;
  createdAt: string;
  updatedAt: string;
}
